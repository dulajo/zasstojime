# Trčíme.cz / Zpožděno – technická specifikace

> Verze 0.3. Tahle verze se soustředí na **datový pohon** (sběr, výpočty, API).
> Frontend a další rozšíření jsou zatím jen v roadmapě.

Veřejný dashboard dochvilnosti Pražské integrované dopravy (PID) nad otevřenými daty Golemio.
Cíl: srozumitelný a **obhajitelný** pohled na denní dochvilnost sítě. Každé zveřejněné číslo musí jít
vysvětlit na stránce „Metodika“.

---

## 1. Datové zdroje

### 1.1 Golemio – polohy vozidel (real-time)
- Endpoint: `GET https://api.golemio.cz/v2/vehiclepositions`
- Autentizace: API klíč (zdarma po registraci), hlavička `X-Access-Token`. Klíč se drží **jen v proměnné prostředí** `GOLEMIO_API_KEY` a nikdy se necommituje.
- Licence: CC BY 4.0, takže v patičce webu musí být uveden zdroj dat.
- Frekvence: každých **30 s** (konfigurovatelné). Rate limit a stránkování (`limit`/`offset`) ověřit ve Fázi 0.
- Formát: GeoJSON `FeatureCollection`, data v `features[].properties`.

Pole, která potřebujeme (**přesné cesty ověřit ve Fázi 0** na reálném payloadu):

| Význam | Předpokládaná cesta v `properties` |
|---|---|
| ID spoje (jízdní řád) | `trip.gtfs.trip_id` |
| Začátek spoje (pro provozní den) | `trip.start_timestamp` |
| Linka | `trip.gtfs.route_short_name` (+ `route_id`) |
| Typ dopravy | `trip.gtfs.route_type` |
| Cílová zastávka (směr) | `trip.gtfs.trip_headsign` |
| ID vozidla | `trip.vehicle_registration_number` / `vehicle_id` |
| Aktuální zpoždění [s] | `last_position.delay.actual` (může být `null`) |
| Čas polohy | `last_position.origin_timestamp` |
| Stav vozidla | `last_position.state_position` |

### 1.2 PID GTFS – statický jízdní řád
- Zdroj: `https://data.pid.cz/PID_GTFS.zip`, import **denně**.
- Použití: číselník linek a jejich kategorie (`routes.txt`: `route_type` + PID rozšíření jako
  `is_night`, `is_regional`, `is_substitute_transport`, existenci sloupců ověřit).
- Kategorie linek se **neodvozují z rozsahů čísel** (ty nepokrývají noční linky, náhradní dopravu,
  školní linky, trolejbusy, přívozy ani lanovku).

---

## 2. Definice (jádro metodiky)

### 2.1 Spoj a provozní den
- **Spoj** = dvojice `(service_date, trip_id)`. Samotné `trip_id` se opakuje každý den, kdy spoj jede.
- **Provozní den** (`service_date`) = lokální datum (Europe/Prague) **začátku spoje**. Spoj,
  který vyjede ve 23:50, patří celý do dne svého výjezdu. Výpočty musí správně zvládnout změnu času (DST).

### 2.2 Platný snapshot
Snapshot se použije pro výpočty, jen když platí všechno z tohoto:
1. `delay.actual` není `null`,
2. `state_position` ∈ {`on_track`, `at_stop`} (seznam stavů ověřit, viz 2.5),
3. `origin_timestamp` není starší než 5 min oproti času stažení (vozidlo se reálně hlásí),
4. `origin_timestamp` je novější než poslední zpracovaný snapshot daného spoje (deduplikace).

Pokud jeden spoj hlásí víc vozidel (spřažené soupravy), bere se snapshot s nejnovějším `origin_timestamp`.

### 2.3 Zpoždění spoje
Pro každý spoj se drží:

| Hodnota | Definice | Použití |
|---|---|---|
| `last_delay` | zpoždění z posledního platného snapshotu | živý stav |
| `max_delay` | **potvrzené maximum**: max přes čas z `min(d[i], d[i-1], d[i-2])`, tj. hodnota musí vydržet 3 po sobě jdoucí platné snapshoty | souhrn zpoždění, rekordman |
| `max_delay_at` | čas, kdy bylo potvrzené maximum dosaženo | „+74 min v 16:42“ |
| `final_delay` | `last_delay` v okamžiku finalizace spoje | index dochvilnosti |

Potvrzené maximum odfiltruje jednorázové výkyvy (GPS skok, chybné spárování) a počítá se
inkrementálně: stačí si u spoje pamatovat poslední dvě platné hodnoty.

**Podezřelé hodnoty:** spoj s `max_delay` > `SUSPECT_DELAY_S` (výchozí 7200 s) dostane příznak
`suspect = true`. Do agregátů se počítá, ale **nikdy se nezobrazí jako Rekordman** bez ručního potvrzení.
Hranici upravit podle dat z Fáze 0 (u vlaků jsou dvouhodinová zpoždění reálná).

**Jízda napřed** (záporné zpoždění): v MVP se počítá jako „včas“ a do souhrnu se přičítá 0.
Spoje s `final_delay ≤ -60 s` se evidují zvlášť (`early_trips`) pro pozdější použití.

### 2.4 Pásma zpoždění (podle `final_delay`)
| Pásmo | Rozsah |
|---|---|
| Včas | < 180 s |
| Mírné | 180–299 s |
| Výrazné | 300–899 s |
| Kritické | ≥ 900 s |

### 2.5 Životní cyklus spoje
```
            první platný snapshot
 (nic) ───────────────────────────▶ running
                                       │
     state = after_track ─────────────▶│──▶ finished
     15 min bez snapshotu ────────────▶│──▶ finished  (finished_by = timeout)
     state = canceled ────────────────▶│──▶ canceled
```
- Spoj, který nikdy neměl platný snapshot, se do statistik nepočítá (eviduje se jen počet).
- Stavy `off_track`, `mismatched`, `invisible`, `unknown`, `before_track*` spoj nefinalizují.
  Jen se nepoužijí pro výpočet zpoždění.
- **Úplný seznam hodnot `state_position` ověřit ve Fázi 0** a tabulku doplnit.

### 2.6 Metriky
Všechny se počítají pro jeden `service_date`.

| Metrika | Výpočet |
|---|---|
| **Index dochvilnosti** | `počet finished s final_delay < 180 / počet finished × 100` |
| **Živý stav** | podíl `running` spojů s `last_delay ≥ 180` (zobrazuje se odděleně od indexu) |
| **Souhrn zpoždění** | `Σ max(0, max_delay)` přes všechny spoje (running i finished) |
| **Průměrné zpoždění zpožděného spoje** | průměr `final_delay` u finished spojů s `final_delay ≥ 180` |
| **Zrušené spoje** | počet `canceled` (zobrazuje se zvlášť, do indexu nevstupuje) |

Formulace na webu musí odpovídat tomu, co měříme. Souhrn zpoždění je **součet zpoždění spojů,
ne čas cestujících** (obsazenost neznáme).

### 2.7 Kategorie (semafor)
Podle GTFS `routes.txt`:

| Kategorie | Pravidlo |
|---|---|
| `metro` | `route_type = 1` (pokrytí ve feedu ověřit, bez dat se nezobrazuje) |
| `tram` | `route_type = 0` |
| `bus_city` | `route_type = 3` a ne regionální |
| `bus_regional` | `route_type = 3` a regionální |
| `train` | `route_type = 2` |
| `other` | trolejbus (11), přívoz (4), lanovka (7), … |

Noční linky a náhradní doprava jsou **příznaky** linky (`is_night`, `is_substitute`), ne kategorie.

### 2.8 Žebříčky
- **Největší souhrnné zpoždění linky:** `Σ max_delay` za linku.
- **Nejvyšší průměrné zpoždění na spoj:** `Σ max_delay / počet spojů`, jen linky s ≥ `MIN_TRIPS` (výchozí 20) dokončenými spoji.
- **Nejhorší dochvilnost linky:** index dochvilnosti linky, jen linky s ≥ `MIN_TRIPS` dokončenými spoji.
- **Rekordman dne:** spoj s nejvyšším `max_delay`, `suspect = false`.

---

## 3. Architektura

```
[ Golemio API ]            [ PID GTFS (denně) ]
       │ polling 30 s               │
       ▼                            ▼
[ Ingestion worker ] ──────▶ [ PostgreSQL ]
  - validace snapshotů          routes, trips_daily,
  - stavový automat spoje       hourly_stats, daily_*_stats,
  - finalizace                  ingest_runs
  - přepočet agregátů                │
  - předpočítané JSON odpovědi       ▼
                              [ Read-only API ] ──▶ CDN ──▶ frontend
```

- **Jeden worker** jako jediný zapisovatel. Stav běžících spojů drží v paměti a průběžně ho ukládá
  (UPSERT) do `trips_daily`. Po restartu stav načte z DB.
- **Stačí čistý PostgreSQL.** TimescaleDB ani Redis nejsou pro MVP potřeba.
- Agregáty se přepočítávají po každém cyklu (dotaz nad `trips_daily` daného dne).
- API vrací předpočítané odpovědi s `Cache-Control: public, s-maxage=30, stale-while-revalidate=60`.
- **Surové payloady** se volitelně ukládají gzipem (retence 7 dní). Slouží k ladění a k přepočtu
  po opravě chyby v logice. Velikost ověřit ve Fázi 0.

---

## 4. Datový model (PostgreSQL)

```sql
-- Číselník linek z PID GTFS (přepisuje se denním importem)
CREATE TABLE routes (
    route_id      VARCHAR(32) PRIMARY KEY,
    short_name    VARCHAR(16) NOT NULL,
    route_type    SMALLINT    NOT NULL,      -- GTFS route_type
    category      VARCHAR(16) NOT NULL,      -- viz 2.7
    is_night      BOOLEAN     NOT NULL DEFAULT false,
    is_substitute BOOLEAN     NOT NULL DEFAULT false,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Jeden řádek = jeden spoj v jednom provozním dni (hlavní tabulka)
CREATE TABLE trips_daily (
    service_date   DATE        NOT NULL,
    trip_id        VARCHAR(64) NOT NULL,
    route_id       VARCHAR(32) NOT NULL,
    route_short    VARCHAR(16) NOT NULL,     -- denormalizace pro rychlé čtení
    category       VARCHAR(16) NOT NULL,
    headsign       TEXT,
    vehicle_id     VARCHAR(32),
    trip_start_at  TIMESTAMPTZ,
    first_seen_at  TIMESTAMPTZ NOT NULL,
    last_seen_at   TIMESTAMPTZ NOT NULL,
    status         VARCHAR(12) NOT NULL,     -- running | finished | canceled
    finished_by    VARCHAR(12),              -- after_track | timeout
    last_delay     INTEGER,
    prev_delay_1   INTEGER,                  -- pro potvrzené maximum
    prev_delay_2   INTEGER,
    max_delay      INTEGER,
    max_delay_at   TIMESTAMPTZ,
    final_delay    INTEGER,
    valid_snapshots INTEGER    NOT NULL DEFAULT 0,
    suspect        BOOLEAN     NOT NULL DEFAULT false,
    PRIMARY KEY (service_date, trip_id)
);
CREATE INDEX idx_trips_route  ON trips_daily (service_date, route_id);
CREATE INDEX idx_trips_status ON trips_daily (status) WHERE status = 'running';

-- Vývoj během dne (vzorkováno z každého cyklu) – podklad pro grafy ve Fázi 3
CREATE TABLE hourly_stats (
    service_date   DATE        NOT NULL,
    hour           SMALLINT    NOT NULL,     -- 0–23, Europe/Prague
    category       VARCHAR(16) NOT NULL,
    samples        INTEGER     NOT NULL,     -- počet (vozidlo × cyklus)
    delayed_samples INTEGER    NOT NULL,     -- z toho last_delay ≥ 180
    sum_delay      BIGINT      NOT NULL,     -- Σ max(0, delay)
    PRIMARY KEY (service_date, hour, category)
);

-- Denní agregát za linku
CREATE TABLE daily_route_stats (
    service_date      DATE        NOT NULL,
    route_id          VARCHAR(32) NOT NULL,
    trips_total       INTEGER     NOT NULL,
    trips_finished    INTEGER     NOT NULL,
    trips_on_time     INTEGER     NOT NULL,
    trips_canceled    INTEGER     NOT NULL,
    sum_max_delay     BIGINT      NOT NULL,
    max_delay         INTEGER     NOT NULL,
    PRIMARY KEY (service_date, route_id)
);

-- Denní agregát za síť a kategorie (category = 'all' pro celou síť)
CREATE TABLE daily_network_stats (
    service_date      DATE        NOT NULL,
    category          VARCHAR(16) NOT NULL,
    trips_total       INTEGER     NOT NULL,
    trips_finished    INTEGER     NOT NULL,
    trips_on_time     INTEGER     NOT NULL,
    trips_canceled    INTEGER     NOT NULL,
    trips_early       INTEGER     NOT NULL,
    sum_max_delay     BIGINT      NOT NULL,
    avg_delayed_delay INTEGER,
    PRIMARY KEY (service_date, category)
);

-- Log běhů workeru (monitoring, detekce děr v datech)
CREATE TABLE ingest_runs (
    id             BIGSERIAL   PRIMARY KEY,
    started_at     TIMESTAMPTZ NOT NULL,
    finished_at    TIMESTAMPTZ,
    ok             BOOLEAN     NOT NULL,
    vehicles       INTEGER,
    valid_snapshots INTEGER,
    error          TEXT
);
```

Retence: `trips_daily` 90 dní (pak stačí denní agregáty), agregáty trvale, `ingest_runs` 30 dní.

---

## 5. Read-only API (MVP)

| Endpoint | Obsah |
|---|---|
| `GET /api/day/{date\|today}` | metriky sítě (2.6) + rozpad podle kategorií |
| `GET /api/day/{date}/leaderboard` | žebříčky (2.8) |
| `GET /api/day/{date}/record` | rekordman dne |
| `GET /api/day/{date}/hourly` | hodinový vývoj podle kategorií |
| `GET /api/health` | čas posledního úspěšného běhu, stáří dat, počet vozidel |

Každá odpověď obsahuje `generated_at` a `data_as_of`, aby frontend mohl ukázat „data k 16:42“.

---

## 6. Provoz a kvalita

- **Monitoring:** alert, pokud poslední úspěšný běh je starší než 5 min. `/api/health` vrací 503, pokud jsou data starší než 10 min.
- **Díry v datech:** výpadky se odvodí z `ingest_runs`. Den s výpadkem delším než 30 min se v API označí `incomplete: true`.
- **Testy:**
  - jednotkové testy stavového automatu spoje, potvrzeného maxima, finalizace, přechodu přes půlnoc a změny času,
  - integrační test nad nahranými payloady z Fáze 0 (deterministický výsledek agregátů).
- **Konfigurace** přes proměnné prostředí: `GOLEMIO_API_KEY`, `DATABASE_URL`, `POLL_INTERVAL_S`,
  `SUSPECT_DELAY_S`, `MIN_TRIPS`, `TRIP_TIMEOUT_S`.
- **Právní:** uvedení zdroje (Golemio, CC BY 4.0). Žádná loga PID ani DPP a nevzbuzovat dojem oficiálního projektu.

---

## 7. Technologie a provoz

### 7.1 Server (worker + API v jedné aplikaci)
| Oblast | Volba |
|---|---|
| Jazyk / runtime | Kotlin, Java 21 |
| Framework | **Micronaut 4** (KSP) |
| Polling | `@Scheduled` + Micronaut HTTP klient |
| JSON | Micronaut Serialization |
| Přístup k DB | **jOOQ** (generování tříd ze schématu) |
| Migrace | **Flyway** (`src/main/resources/db/migration/V1__init.sql`, …) |
| GTFS import | Apache Commons CSV + `COPY` do PostgreSQL |
| API kontrakt | Micronaut OpenAPI (generuje se při kompilaci) |
| Metriky | Micrometer → Prometheus |
| Testy | JUnit 5 / Kotest, **Micronaut Test Resources** (Postgres v Dockeru) |
| Build | Gradle (Kotlin DSL) |

Worker a API běží v jednom procesu. Kód se ale dělí do oddělených modulů (`ingest`, `aggregation`, `api`, `gtfs`),
aby šly později rozdělit.

### 7.2 Databáze
PostgreSQL 16.

### 7.3 Klient (web)
| Oblast | Volba |
|---|---|
| Framework | **Next.js** (App Router) + **TypeScript** (strict) |
| Styly | Tailwind CSS |
| Data | TanStack Query (refresh každých 30 s) |
| Typy API | generované z OpenAPI serveru (`openapi-typescript`), změna API rozbije build frontendu |
| OG obrázky | Next.js `ImageResponse` |
| Hosting | Cloudflare (Workers/Pages). Alternativa: Vercel, ale bezplatný tarif zakazuje komerční použití. |

### 7.4 Provoz
```
Internet ──▶ Cloudflare (DNS, CDN, cache API 30 s)
               │                       │
               ▼                       ▼
        frontend (Cloudflare)    Cloudflare Tunnel
                                       │ (odchozí spojení z homelabu, žádný otevřený port)
                                       ▼
                         homelab – izolovaná VM/LXC (oddělená VLAN)
                         Docker Compose: cloudflared, app (Micronaut), PostgreSQL
                                       │
                         správa jen přes WireGuard + NPM:
                         Grafana, Prometheus, přístup k DB
```
- **Veřejně** je dostupné jen API (přes tunel) a frontend (na Cloudflare). Virální návštěvnost obslouží CDN
  a homelab dostane jen pár požadavků za minutu.
- **Správa** (Grafana, DB, logy) je dostupná jen přes VPN, tak jako zbytek homelabu.
- **Zálohy:** denní `pg_dump` mimo homelab (cloudové úložiště).
- **Přenositelnost:** celé nasazení je Docker Compose, takže přesun na VPS je otázka obnovení zálohy a změny tunelu.
- **Doména:** nová `.cz` doména od českého registrátora (Cloudflare `.cz` neprodává),
  nameservery přepnuté na Cloudflare (bezplatný tarif).
- **Návštěvnost:** Cloudflare Web Analytics (bez cookies, bez zátěže homelabu).
- **Technický monitoring:** Grafana dashboard (zpoždění sběru, počet vozidel za cyklus, chyby Golemia, velikost DB)
  a alerty podle oddílu 6.

---

## 8. Roadmapa

**Fáze 0 – Ověření dat**
- Získat API klíč, povolit síťový přístup na `api.golemio.cz` a `data.pid.cz`.
- Nahrát 2–3 h reálných payloadů a ověřit cesty polí, hodnoty `state_position`, četnost `null`,
  pokrytí metra a vlaků, rate limit a velikost odpovědi.
- Doplnit zjištění do této specifikace (oddíly 1.1, 2.5, 2.7).

**Fáze 1 – Pohon**
- Import PID GTFS (`routes`).
- Ingestion worker se stavovým automatem spoje, potvrzeným maximem a finalizací.
- Agregace (`hourly_stats`, `daily_route_stats`, `daily_network_stats`), read-only API, monitoring a testy.

**Fáze 2 – Dashboard**
- Jednostránkový responzivní web: hero metriky, semafor kategorií, žebříčky, rekordman, stránka Metodika.
- OG obrázky pro sdílení (verzované URL).

**Fáze 3 – Rozšíření**
- Grafy vývoje během dne, detail a vyhledání linky, historie a srovnání dnů.

**Později (zatím bez specifikace)**
- Přepočty zpoždění na srozumitelné jednotky času („za to by se stihly celé Pelíšky“), na webu občas i s pivním vtipem, na sociálních sítích bez něj.
- Automatické příspěvky na sociální sítě s ručním schvalováním a případná monetizace.

---

## 9. Otevřené otázky

1. Přesná struktura payloadu, seznam `state_position` a pokrytí metra/vlaků (Fáze 0).
2. Výsledný název a doména (Trčíme.cz / Zpožděno / zasstojime).
3. Co ukazovat na webu po půlnoci, kdy má nový den skoro nulová čísla (např. do 4:00 včerejšek).
