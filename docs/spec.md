# Specifikace – Fáze 1 (pohon)

> Stav: **návrh k review**. Po schválení se podle ní implementuje.
> Pravidla výpočtů jsou v [metodika.md](metodika.md), schéma DB, API a konfigurace v [technicke.md](technicke.md),
> zdůvodnění rozhodnutí v [adr/](adr/README.md), ověření na reálných datech v [faze0.md](faze0.md).
> Tento dokument je vstupní bod: říká **co** se staví, **v jakém pořadí** a **kdy je to hotové**.

## 1. Účel a rozsah

Server, který každých 30 s čte polohy vozidel PID z Golemia, měří zpoždění na odjezdech ze zastávek,
ukládá je a poskytuje read-only API pro dashboard dochvilnosti.

| V rozsahu Fáze 1 | Mimo rozsah |
|---|---|
| Import PID GTFS (linky, zastávky) | Frontend (Fáze 2) |
| Sběr a zpracování snímků, stavový automat spoje | Trasy s přestupy |
| `trips_daily`, `stop_events`, denní a hodinové souhrny | Sociální sítě, přepočty na jednotky času |
| Read-only API (dashboard, žebříčky, health) | Uživatelské účty |
| Monitoring, zálohy, nasazení do homelabu | Ověření metra (ADR-0013, čeká na mimořádnost) |

## 2. Pojmy

| Pojem | Význam |
|---|---|
| Snímek | Jedna odpověď Golemia (všechna vozidla v daném okamžiku) |
| Spoj | `(service_date, trip_id)` – ADR-0003 |
| Provozní den | Lokální datum výjezdu spoje – ADR-0004 |
| Stanice (uzel) | `asw_node_id` z PID GTFS; seskupuje nástupiště – ADR-0045 |
| Průjezd zastávkou | Záznam v `stop_events`: plán + zpoždění příjezdu a odjezdu – ADR-0044 |
| Odjezd | Průjezd se změřeným zpožděním odjezdu; jednotka indexu dochvilnosti – ADR-0033 |
| Typ dne | `workday` / `saturday` / `sunday_holiday` – ADR-0046 |

## 3. Funkční požadavky

### 3.1 Import jízdního řádu
| ID | Požadavek | ADR |
|---|---|---|
| FR-01 | Stáhnout `https://data.pid.cz/PID_GTFS.zip` při startu (pokud je import starší než 24 h) a každou noc ve 04:30. | 0002 |
| FR-02a | Naplnit `gtfs_trips` (`trip_id`, linka, čas výjezdu z první zastávky v `stop_times.txt`, včetně časů ≥ 24:00). | 0050 |
| FR-02 | Naplnit `routes` (kategorie podle `route_type` + `is_regional`; příznaky `is_night`, `is_substitute`) a `stops` (`node_id` = `asw_node_id`, prázdné = technický bod). UPSERT, nic nemazat. | 0002, 0045 |
| FR-03 | Selhání importu nesmí zastavit sběr; použije se poslední úspěšný import a nastaví se metrika/alert. | — |
| FR-04 | Naplnit `calendar_days` na 2 roky dopředu včetně českých státních svátků (Velikonoce počítat). | 0046 |

### 3.2 Sběr a zpracování (každý cyklus)
| ID | Požadavek | ADR |
|---|---|---|
| FR-10 | Každých `POLL_INTERVAL_S` (30 s) stáhnout `/v2/vehiclepositions?limit=10000&includeNotTracking=true` s gzipem a hlavičkou `X-Access-Token`. Cykly se nepřekrývají. | 0001 |
| FR-11 | Každý cyklus zapsat do `ingest_runs` (čas, úspěch, počet vozidel, počet nových průjezdů, chyba). | 0030 |
| FR-12 | Zpracovat snímek podle algoritmu v kapitole 4. | 0033–0042 |
| FR-13 | Do DB zapisovat jen změněné spoje (`trips_daily`, UPSERT) a nové nebo změněné průjezdy (`stop_events`, UPSERT). Zápis jednoho cyklu v jedné transakci. | 0038 |
| FR-14 | Po restartu obnovit stav dnešních a včerejších neukončených spojů z DB. Ztráta rozpracované zastávky je přípustná. | 0038 |
| FR-15 | Volitelně (`RAW_ARCHIVE=true`) ukládat surové snímky, v noci je zabalit po dnech, držet 7 dní. | 0018 |

### 3.3 Souhrny
| ID | Požadavek | ADR |
|---|---|---|
| FR-20 | Po každém cyklu přepočítat v paměti živé metriky dnešního dne (síť, kategorie, linky, rekordman). | 0017, 0038 |
| FR-21 | Každých `DAILY_FLUSH_S` (300 s) zapsat `daily_network_stats`, `daily_route_stats`, `hourly_stats` dnešního dne. | 0038 |
| FR-22 | Den D se uzavře (finální zápis souhrnů) v D+1 v 06:00, kdy dojedou noční spoje. | 0004 |
| FR-23 | Každou noc spočítat `stop_route_hourly` za uplynulý den (přičtení do měsíce), založit oddíl `stop_events` na další měsíc, smazat oddíly starší 13 měsíců. | 0044 |

### 3.4 API
| ID | Požadavek | ADR |
|---|---|---|
| FR-30 | Endpointy dle [technicke.md – API](technicke.md#api). Každá odpověď obsahuje `generated_at`, `data_as_of`, `incomplete`. | 0017 |
| FR-31 | Dashboardové endpointy vracejí předpočítaný JSON z paměti; `Cache-Control: public, s-maxage=30, stale-while-revalidate=60`. | 0017 |
| FR-32 | Zrušeno: „Moje trasa“ není součástí webu. | 0058 |
| FR-33 | `/api/health` vrací 503, pokud poslední úspěšný cyklus je starší než 10 min. | 0030 |
| FR-34 | Server publikuje OpenAPI (`/q/openapi`). | 0024 |
| FR-35 | API je read-only, bez autentizace; CORS jen pro doménu frontendu. | 0048 |

## 4. Algoritmus zpracování snímku

```
fetch_time = now()
snapshot = GET golemio                                   // při chybě: ingest_runs(ok=false), konec cyklu

// 1) výběr vozidla, když je jeden spoj hlášen víckrát
group features by trip_id
for each group: pick vehicle with min(seq) where seq >= trip.max_seq   // ADR-0052
                (nový spoj: min(seq)); ostatní ignorovat a počítat do metriky

for each feature f:
    service_date = local_date(f.trip.start_timestamp)    // null → přeskočit, počítat do metriky
    if gtfs_trips[trip_id].first_departure >= 24:00: service_date -= 1 day   // ADR-0050
    trip = state[(service_date, trip_id)] ?: new Trip(...)  // linka, kategorie, směr z routes/feedu
    trip.last_seen_at = fetch_time
    st = f.last_position.state_position

    // 2) zrušení
    if st == canceled or f.is_canceled == true:
        close_pending(trip, f); trip.status = canceled; continue

    // 3) dojetí
    if st in {after_track, after_track_delayed}:
        if trip.status == running:
            close_pending(trip, f, terminus = true)       // uloží příjezd na konečnou
            trip.status = finished; trip.finished_by = after_track; trip.finished_at = fetch_time
        continue

    // 4) neměřené stavy
    if st not in {on_track, at_stop}: continue          // before_track*, off_track

    // 5) validace (metodika: Platný snapshot)
    if fetch_time - origin_timestamp > 300 s: continue
    if origin_timestamp <= trip.last_origin_ts: continue
    seq = f.last_stop.sequence
    if seq == null or seq < trip.max_seq: continue       // ADR-0040
    trip.last_origin_ts = origin_timestamp
    trip.last_valid_at = fetch_time

    // 6) znovuotevření
    if trip.status == finished and fetch_time - trip.finished_at <= 900 s:
        trip.status = running                            // ADR-0042
    if trip.status != running and trip.status != new: continue
    trip.status = running

    // 7) posun na další zastávku → uzavřít předchozí
    if seq > trip.max_seq:
        close_pending(trip, f)
        trip.pending = Pending(seq, stop_id, planned_arr, planned_dep)
        trip.max_seq = seq

    // 8) průběžná aktualizace rozpracované zastávky (ADR-0041)
    if f.delay.last_stop_arrival != null:   trip.pending.arr = f.delay.last_stop_arrival
    if f.delay.last_stop_departure != null: trip.pending.dep = f.delay.last_stop_departure

// 9) timeout – běží jen po úspěšném cyklu, takže výpadek sběru spoje neukončí
for each running trip with fetch_time - trip.last_valid_at > TRIP_TIMEOUT_S (900 s):
    close_pending(trip); trip.status = finished; trip.finished_by = timeout

persist_changes(); recompute_live(); write ingest_runs(ok=true)

close_pending(trip, f, terminus = false):
    p = trip.pending; if p == null: return
    node = stops[p.stop_id].node_id
    if node == null: trip.pending = null; return          // technický bod (ADR-0045)
    if p.dep != null and not plausible(trip, p): p.dep = null   // ADR-0035
    if p.arr == null and p.dep == null: trip.pending = null; return
    emit StopEvent(service_date, trip_id, p.seq, route_id, p.stop_id, node, p.planned_arr, p.planned_dep, p.arr, p.dep)
    if p.dep != null:
        update trip counters (dep_total, on_time, early, late, late_sum), last_delay, max_delay(+at, +stop)
        if trip.max_delay > ALERT_DELAY_S: trip.flagged = true                // ADR-0055
        trip.last_dep_delay = p.dep; trip.last_dep_origin_ts = origin_timestamp aktuálního snímku
    trip.pending = null

plausible(trip, p):
    // nárůst zpoždění oproti poslednímu změřenému odjezdu ≤ uplynulý čas + MAX_DELAY_JUMP_S
    if trip.last_dep_delay == null: return true           // první odjezd spoje
    elapsed = origin_timestamp - trip.last_dep_origin_ts
    return abs(p.dep - trip.last_dep_delay) <= elapsed + MAX_DELAY_JUMP_S   // ADR-0035, ADR-0051
```

Pozn.: první zastávka spoje má jen odjezd, konečná jen příjezd. Obojí se ukládá.

Upřesnění z implementace (`TripProcessor`):

- `close_pending(trip, f, terminus = true)`: když `f.last_stop.sequence` > `trip.max_seq`, uzavře se nejdřív rozpracovaná zastávka
  a konečná se založí jako nová; konečná dostane příjezd z `f.delay.last_stop_arrival` a odjezd se zahodí.
- `origin_timestamp` pro uzavření mimo platný snímek: u dojetí a zrušení novější z `f.origin_timestamp` a `trip.last_origin_ts`,
  u timeoutu `trip.last_origin_ts`.
- Vozidla se seskupují podle `(service_date, trip_id)`: totéž `trip_id` jezdí každý den, ráno může být ve feedu včerejší
  dojetý spoj i dnešní před výjezdem.
- Spoj se zapisuje do DB, jen když se změní stav, uzavře zastávka nebo vozidlo (FR-13); spoj jen ve stavu `new` se nezapisuje.

## 5. Okrajové případy

| Případ | Chování | ADR / FR |
|---|---|---|
| Golemio nedostupné / timeout / 5xx / 401 | Cyklus se přeskočí, `ingest_runs.ok=false`; alert po 5 min bez úspěchu. Timeout spojů se nevyhodnocuje, dokud nepřijde úspěšný snímek. | FR-11, 0030 |
| Výpadek sběru delší než 15 min | Po obnovení první úspěšný snímek nejdřív aktualizuje jedoucí spoje, teprve pak se vyhodnotí timeout. Spoj, který mezitím dojel, se ukončí timeoutem. | 0042 |
| Restart serveru | Stav se obnoví z DB; rozpracovaná zastávka se ztratí | FR-14 |
| Spoj přes půlnoc / výjezd ≥ 24:00 v jízdním řádu | Celý patří do servisního dne podle jízdního řádu | 0050 |
| Změna času (23 h / 25 h den) | Dny a hodiny v Europe/Prague; na podzim se hodina 02:00 sečte, na jaře chybí | 0050 |
| Dvě vozidla hlášená pro jeden spoj | Vozidlo, které navazuje na průběh spoje | 0052 |
| Přeskok zpět na trase (i dlouhodobý) | Snímek ignorován. Dlouhé přeskoky v záznamu byly vozidla jedoucí opačným směrem, tedy chybně přiřazená. | 0040 |
| Nereálný nárůst nebo pokles zpoždění | Odjezd se zahodí | 0035, 0051 |
| Zrušení za jízdy | Změřené odjezdy se počítají, spoj `canceled` | 0054 |
| Rekordman > 2 h | Příznak „neověřeno“, metrika pro alert | 0055 |
| Spoj zmizí bez `after_track` | Timeout 15 min | 0042 |
| `after_track` a pak znovu na trase | Znovuotevření do 15 min | 0042 |
| Zrušení za jízdy | Změřené průjezdy zůstanou, spoj `canceled` | 0009 |
| Linka nebo zastávka chybí v GTFS | Kategorie `other`; zastávka bez uzlu se neukládá; metrika + log | FR-02 |
| Změna jízdního řádu (nová `trip_id`) | Nic zvláštního; spoj je klíčovaný provozním dnem a `trip_id` | 0003 |
| Výpadek sběru > 30 min za den | `incomplete: true` v odpovědích za ten den | FR-30 |

## 6. Nefunkční požadavky

| ID | Požadavek |
|---|---|
| NFR-01 | Zpracování jednoho cyklu (parse + výpočet + zápis) < 5 s na homelab HW. |
| NFR-02 | Stáří dat v API typicky ≤ 60 s. |
| NFR-03 | Paměť serveru < 1 GB (cca 40 tis. spojů denně v paměti). |
| NFR-04 | Růst DB ≤ ~2,5 GB/měsíc; retence dle [technicke.md](technicke.md#datový-model). |
| NFR-05 | Odpověď API z paměti p95 < 50 ms. |
| NFR-06 | API klíč jen v proměnné prostředí / `.env` mimo git; nikdy v logu. |
| NFR-07 | Všechny časy uložené jako `TIMESTAMPTZ`; výpočty dnů a hodin v Europe/Prague. |
| NFR-08 | Atribuce zdroje (Golemio, CC BY 4.0) v API (`source`) i na webu. |
| NFR-09 | Metriky Prometheus: délka cyklu, počet vozidel, nové průjezdy, zahozené odjezdy (podle důvodu), ignorovaná vozidla, chyby Golemia, stáří dat, `flagged_trips`, velikost DB. |
| NFR-10 | Alerty přes Grafana Alerting → Telegram (ADR-0030). |

## 7. Testování a akceptační kritéria

| ID | Kritérium |
|---|---|
| AC-01 | Jednotkové testy stavového automatu pokrývají všechny řádky kapitoly 5. |
| AC-02 | Testy pro `close_pending`: běžný odjezd, konečná (jen příjezd), technický bod, nereálný skok, `null` hodnoty. |
| AC-03 | Testy výpočtu provozního dne a hodin pro 23h a 25h den, spoj přes půlnoc a spoj s výjezdem 24:13 v jízdním řádu. |
| AC-09 | Test výpadku: 20 min bez úspěšného snímku nesmí ukončit spoje, které po obnovení dál jedou. |
| AC-10 | Test výběru vozidla: dvě vozidla na jednom spoji se sekvencemi 9 a 1 → použije se navazující. |
| AC-04 | Testy `calendar_days`: všechny české svátky 2026–2027 včetně Velikonočního pondělí a Velkého pátku. |
| AC-05 | Fixture: 30 min reálných snímků (xz, ~4 MB) v `src/test/resources`. Přehrání dá deterministický výsledek; počet průjezdů, index po kategoriích a top 5 zpoždění se ověří snapshot testem. |
| AC-06 | Integrační test proti PostgreSQL (Dev Services): migrace, UPSERT, oddíly `stop_events`, souhrny a API dashboardu na fixture. |
| AC-07 | Po nasazení 1a: 24 h běhu bez pádu, `ingest_runs` úspěšnost ≥ 99 %, alert ověřen vypnutím sítě. |
| AC-08 | Kontrola proti realitě: 3 ručně vybrané spoje porovnané s odjezdovou tabulí / aplikací PID Lítačka. |

## 8. Pořadí implementace a nasazení

| Krok | Obsah | Hotovo když |
|---|---|---|
| 1a-1 | Kostra: Gradle, Quarkus, Kotlin, Docker Compose (server + PostgreSQL), Flyway `V1__init.sql` | `./gradlew build` a `docker compose up` projdou |
| 1a-2 | Import GTFS + `calendar_days` | FR-01–04, AC-04 |
| 1a-3 | Sběr + algoritmus + `trips_daily` / `stop_events` + `ingest_runs` | FR-10–14, AC-01–03, AC-05–06 |
| 1a-4 | Metriky, `/api/health`, nasazení do homelabu (bez veřejného přístupu) | **AC-07 → od teď běží historie** |
| 1b-1 | Souhrny a živé metriky | FR-20–23 |
| 1b-2 | API dashboardu, OpenAPI | FR-30–35, NFR-05 |
| 1b-3 | Cloudflare Tunnel, zálohy, AC-08 | Veřejné API |

## 9. Rizika a otevřené otázky

| # | Riziko / otázka | Dopad | Opatření |
|---|---|---|---|
| R1 | Golemio změní formát nebo omezí přístup | Sběr stojí | Alert; parsování tolerantní k novým polím |
| R2 | Rate limit Golemia neznámý | Odmítnuté požadavky | 1 požadavek / 30 s; sledovat 429 |
| R3 | Výpadky homelabu | Díry v historii | `incomplete`, poctivé „měřeno N z M dní“ |
| R4 | Metro má možná dopočítané zpoždění | Zavádějící čísla | ADR-0013: na webu skryté do ověření |
| R5 | Odhady objemu jsou z večerního záznamu | DB roste rychleji | Změřit po prvním dni; NFR-04 |
| Q1 | Výsledný název a doména | — | Před Fází 2 |
| Q2 | Co ukazovat po půlnoci (nový den má skoro nulová čísla) | UX | Před Fází 2 |
