# Technické detaily

## Architektura

```
[ Golemio API ]          [ PID GTFS (denně) ]
       │ polling 30 s             │
       ▼                          ▼
[ Server (Quarkus) ] ─────▶ [ PostgreSQL ]
  ingest  – validace, stavový automat spoje, finalizace
  gtfs    – import linek, zastávek a časů výjezdů spojů
  aggregation – živá čísla v paměti; denní tabulky do DB každých 5 min
  api     – předpočítané JSON odpovědi
       │
       ▼
  Cloudflare Tunnel ─▶ Cloudflare CDN ─▶ frontend (Next.js na Cloudflare)
```

Stav dnešních spojů drží server v paměti (včetně rozpracované zastávky). Do `trips_daily` ukládá (UPSERT) jen spoje, které se v cyklu změnily. Po restartu stav načte z DB; přijde nanejvýš o právě rozpracovaný odjezd.

## Server – knihovny

| Účel | Quarkus extension / knihovna |
|---|---|
| Kotlin | `quarkus-kotlin` |
| REST API | `quarkus-rest` + `quarkus-rest-kotlin-serialization` |
| Volání Golemia a stažení GTFS | `java.net.http.HttpClient` z JDK (gzip a proxy pod kontrolou, tělo se dá archivovat beze změny) |
| Polling | `quarkus-scheduler` |
| DB | `quarkus-jdbc-postgresql` + jOOQ |
| Migrace | `quarkus-flyway` |
| OpenAPI | `quarkus-smallrye-openapi` |
| Metriky | `quarkus-micrometer-registry-prometheus` |
| GTFS CSV | Apache Commons CSV |
| Testy | JUnit 5, Dev Services (Postgres v Dockeru), `org.tukaani:xz` pro fixture |
| Build | Gradle (Kotlin DSL), Java 21 |

## Datový model

Schéma je ve Flyway migraci [`server/src/main/resources/db/migration/V1__init.sql`](../server/src/main/resources/db/migration/V1__init.sql);
jOOQ z ní generuje třídy při buildu (`DDLDatabase`, bez běžící DB). Oproti původnímu návrhu:

| Změna | Důvod |
|---|---|
| `gtfs_trips.first_departure_s INTEGER` místo `first_departure INTERVAL` | Jednodušší mapování; hodnota může být ≥ 86 400 (výjezd ≥ 24:00) |
| `trips_daily.finished_at`, `last_valid_at`, `last_origin_at`, `last_dep_origin_at` | Obnova po restartu (FR-14): znovuotevření, timeout, deduplikace a kontrola nereálného skoku pokračují tam, kde skončily |
| `trips_daily.last_stop_seq` = poslední **uzavřená** zastávka | Po restartu se z ní obnoví nejvyšší sekvence; rozpracovaná zastávka se změří znovu |
| `ingest_runs.stop_events` | FR-11: počet nových průjezdů v cyklu |

Oddíly `stop_events` se jmenují `stop_events_yYYYYmMM`. Server při startu a každou noc ve 03:15 zakládá minulý, tento a příští měsíc
a maže oddíly starší než 13 měsíců; před zápisem průjezdu do dosud neznámého měsíce oddíl založí sám.

Do `trips_daily` se zapisují jen spoje, které už vyjely nebo byly zrušeny (spoj jen ve stavu `before_track` zůstává v paměti).
Spoj se zapíše, když se změní stav, uzavře zastávka nebo vozidlo; pouhé „viděn znovu“ zápis nevyvolá (FR-13).
`verified` a `excluded` nastavuje správce, UPSERT sběru je nepřepisuje.

## API

Všechny odpovědi obsahují `generated_at` a `data_as_of`. Cache: `public, s-maxage=30, stale-while-revalidate=60`.

| Endpoint | Obsah |
|---|---|
| `GET /api/day/{date\|today}` | Metriky sítě + rozpad podle kategorií |
| `GET /api/day/{date}/leaderboard` | Žebříčky |
| `GET /api/day/{date}/record` | Rekordman dne |
| `GET /api/day/{date}/hourly` | Hodinový vývoj |
| `GET /api/lines?q=` | Vyhledání linky |
| `GET /api/lines/{line}/stops` | Stanice linky (uzly) v pořadí trasy |
| `GET /api/lines/{line}/departures?from={node}&day_type=` | Plánované odjezdy ze stanice (z posledních 14 dní) |
| `GET /api/my-route?line=&from=&to=&time=&day_type=&period=` | „Moje trasa“: metriky + jednotlivé dny |
| `GET /api/health` | Poslední úspěšný běh, stáří dat; 503 při datech starších než 10 min |

## Konfigurace (proměnné prostředí)

| Proměnná | Výchozí | Význam |
|---|---|---|
| `GOLEMIO_API_KEY` | — | API klíč, **nikdy necommitovat** |
| `DATABASE_URL` | — | Připojení k PostgreSQL |
| `POLL_INTERVAL_S` | 30 | Interval sběru |
| `TRIP_TIMEOUT_S` | 900 | Ukončení spoje bez snapshotu |
| `MAX_DELAY_JUMP_S` | 120 | Tolerance nárůstu zpoždění nad uplynulý čas |
| `ALERT_DELAY_S` | 7200 | Nad touto hodnotou dostane spoj příznak „neověřeno“ a metriku pro alert (ADR-0055) |
| `DAILY_FLUSH_S` | 300 | Interval zápisu denních tabulek do DB |
| `MIN_TRIPS` | 20 | Minimum spojů pro žebříčky |
| `RAW_ARCHIVE` | false | Ukládat surové snímky (ADR-0018) |
| `CORS_ORIGIN` | — | Doména frontendu |

## Struktura serveru

```
server/
  build.gradle.kts                    Gradle (Kotlin DSL), Quarkus, jOOQ codegen z migrací
  src/main/kotlin/cz/zasstojime/
    Bootstrap.kt                      start: oddíly → kalendář → jízdní řád → obnova stavu → sběr
    golemio/                          HTTP klient Golemia, parser snímku
    gtfs/                             import PID GTFS, kategorie, kalendář se svátky
    ingest/                           TripProcessor (stavový automat, kap. 4 spec), cyklus sběru, zápis do DB
    persistence/                      jOOQ, oddíly stop_events, metrika velikosti DB
    api/                              /api/health
  src/main/resources/db/migration/    Flyway
  src/test/resources/replay/          30 min reálných snímků + výřez GTFS (AC-05)
  tools/make_replay_fixture.py        tvorba fixture ze záznamu
```

| Endpoint | Obsah |
|---|---|
| `/api/health` | Stav sběru (FR-33) |
| `/q/openapi` | OpenAPI (FR-34) |
| `/q/metrics` | Prometheus (NFR-09) |

Metriky: `ingest_cycle_seconds`, `ingest_cycles_total{result}`, `ingest_vehicles`, `ingest_stop_events_total`,
`ingest_snapshots_skipped_total{reason}` (vč. `duplicate_vehicle` = ignorovaná vozidla), `ingest_departures_dropped_total{reason}`,
`golemio_errors_total{kind}`, `ingest_data_age_seconds`, `trips_running`, `flagged_trips`, `gtfs_import_last_success_seconds`,
`gtfs_import_failures_total`, `gtfs_unknown_routes_total`, `db_size_bytes`.

## Vývoj

```
cd server
./gradlew test          # jednotkové testy + integrační proti PostgreSQL (Dev Services, potřebuje Docker)
./gradlew quarkusDev    # dev režim; Postgres spustí Dev Services, GOLEMIO_API_KEY z prostředí
./gradlew build         # build/quarkus-app pro Docker obraz
```

Po vědomé změně metodiky se snapshot přehrání aktualizuje `UPDATE_SNAPSHOTS=1 ./gradlew test --tests '*ReplayTest'`
a diff `src/test/resources/replay/expected-summary.txt` se zkontroluje v review.

Nasazení: `cp .env.example .env` (vyplnit), `cd server && ./gradlew build && cd .. && docker compose up -d --build`.

## Kvalita a provoz

| Oblast | Pravidlo |
|---|---|
| Alert | Poslední úspěšný běh starší než 5 min |
| Díry v datech | Pro provozní den: součet mezer mezi po sobě jdoucími **úspěšnými** běhy v `ingest_runs`, které jsou delší než 90 s (od každé se odečte 30 s). Součet > 30 min → `incomplete: true` |
| Změna času | Hodina = hodina plánovaného odjezdu v Europe/Prague. Na podzim se dvě hodiny 02:00 sečtou do jedné, na jaře hodina 02:00 chybí. Přijatelné, uvedeno v metodice. |
| Součty | `sum_max_delay` se počítá jako `SUM(GREATEST(0, max_delay))` |
| Alerty | Grafana Alerting → Telegram (ADR-0030) |
| Jednotkové testy | Stavový automat (včetně znovuotevření), měření odjezdů, přeskok zpět, nereálné skoky, půlnoc, změna času |
| Integrační testy | Nahrané payloady z Fáze 0 → deterministické agregáty |
| Zálohy | Denní `pg_dump` mimo homelab |
| Noční úlohy | Souhrn `stop_route_hourly`, založení oddílu na další měsíc, mazání oddílů starších než 13 měsíců |

## Nasazení

```
Internet ─▶ Cloudflare (DNS, CDN) ─┬─▶ frontend (Cloudflare)
                                   └─▶ Cloudflare Tunnel
                                            │ odchozí spojení, žádný otevřený port
                                            ▼
                     homelab: izolovaná VM/LXC (vlastní VLAN)
                     Docker Compose: cloudflared + server + PostgreSQL
                                            │
                     správa jen přes WireGuard + NPM: Grafana, Prometheus, DB
```
