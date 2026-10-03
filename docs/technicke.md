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
| Volání Golemia | `quarkus-rest-client` |
| Polling | `quarkus-scheduler` |
| DB | `quarkus-jdbc-postgresql` + jOOQ |
| Migrace | `quarkus-flyway` |
| OpenAPI | `quarkus-smallrye-openapi` |
| Metriky | `quarkus-micrometer-registry-prometheus` |
| GTFS CSV | Apache Commons CSV |
| Testy | JUnit 5, Dev Services (Postgres v Dockeru) |
| Build | Gradle (Kotlin DSL), Java 21 |

## Datový model

Výchozí návrh. Po založení projektu se přesune do Flyway migrace `V1__init.sql`.

| Tabulka | 1 řádek = | Retence | Odhad velikosti |
|---|---|---|---|
| `routes` | linka z PID GTFS | přepis denně | ~900 řádků |
| `stops` | zastávka / nástupiště z PID GTFS | přepis denně | ~20 tis. řádků |
| `gtfs_trips` | spoj z jízdního řádu: linka, čas výjezdu | přepis denně | ~90 tis. řádků |
| `calendar_days` | den s typem (pracovní / sobota / neděle a svátek) | trvale | 365 řádků/rok |
| `trips_daily` | spoj × provozní den | 13 měsíců | ~40 tis. řádků/den |
| `stop_events` | průjezd spoje zastávkou | 13 měsíců, oddíly po měsících | ~0,5 mil. řádků/den, ~2 GB/měsíc |
| `stop_route_hourly` | měsíc × linka × stanice × typ dne × hodina | trvale | malá |
| `hourly_stats` | den × hodina odjezdu × kategorie | trvale | malá |
| `daily_route_stats` | den × linka | trvale | ~900 řádků/den |
| `daily_network_stats` | den × kategorie (`all` = celá síť) | trvale | 7 řádků/den |
| `ingest_runs` | jeden běh sběru | 13 měsíců (pokrytí dat pro „Moje trasa“) | ~2 900 řádků/den |

```sql
CREATE TABLE routes (
    route_id      VARCHAR(32) PRIMARY KEY,
    short_name    VARCHAR(16) NOT NULL,
    route_type    SMALLINT    NOT NULL,
    category      VARCHAR(16) NOT NULL,
    is_night      BOOLEAN     NOT NULL DEFAULT false,
    is_substitute BOOLEAN     NOT NULL DEFAULT false,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE stops (
    stop_id       VARCHAR(32) PRIMARY KEY,   -- nástupiště, např. U142V5S
    name          TEXT        NOT NULL,
    node_id       INTEGER,                   -- asw_node_id = stanice; NULL = technický bod
    platform_code VARCHAR(8),
    lat           DOUBLE PRECISION,
    lon           DOUBLE PRECISION,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_stops_node ON stops (node_id);

CREATE TABLE gtfs_trips (
    trip_id         VARCHAR(64) PRIMARY KEY,
    route_id        VARCHAR(32) NOT NULL,
    first_departure INTERVAL    NOT NULL,     -- z stop_times, může být ≥ 24:00 (ADR-0050)
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE calendar_days (
    day           DATE        PRIMARY KEY,
    day_type      VARCHAR(16) NOT NULL CHECK (day_type IN ('workday','saturday','sunday_holiday')),
    holiday_name  TEXT
);

CREATE TABLE trips_daily (
    service_date    DATE        NOT NULL,
    trip_id         VARCHAR(64) NOT NULL,
    route_id        VARCHAR(32) NOT NULL,
    route_short     VARCHAR(16) NOT NULL,
    category        VARCHAR(16) NOT NULL,
    headsign        TEXT,
    vehicle_id      VARCHAR(32),
    trip_start_at   TIMESTAMPTZ,
    first_seen_at   TIMESTAMPTZ NOT NULL,
    last_seen_at    TIMESTAMPTZ NOT NULL,
    status          VARCHAR(12) NOT NULL CHECK (status IN ('running','finished','canceled')),
    finished_by     VARCHAR(12) CHECK (finished_by IN ('after_track','timeout')),
    last_delay      INTEGER,
    max_delay       INTEGER,
    max_delay_at    TIMESTAMPTZ,
    max_delay_stop  VARCHAR(32),            -- GTFS stop_id
    last_stop_seq   INTEGER,                -- poslední změřená zastávka
    dep_total       INTEGER     NOT NULL DEFAULT 0,
    dep_on_time     INTEGER     NOT NULL DEFAULT 0,
    dep_early       INTEGER     NOT NULL DEFAULT 0,
    dep_late        INTEGER     NOT NULL DEFAULT 0,
    dep_late_sum    BIGINT      NOT NULL DEFAULT 0,
    valid_snapshots INTEGER     NOT NULL DEFAULT 0,
    flagged         BOOLEAN     NOT NULL DEFAULT false,   -- max_delay > ALERT_DELAY_S (ADR-0055)
    verified        BOOLEAN     NOT NULL DEFAULT false,   -- správce potvrdil
    excluded        BOOLEAN     NOT NULL DEFAULT false,   -- správce vyřadil z rekordmana
    PRIMARY KEY (service_date, trip_id),
    CHECK (dep_total = dep_on_time + dep_early + dep_late),
    CHECK (dep_on_time >= 0 AND dep_early >= 0 AND dep_late >= 0 AND dep_late_sum >= 0)
);
CREATE INDEX idx_trips_route   ON trips_daily (service_date, route_id);
CREATE INDEX idx_trips_running ON trips_daily (status) WHERE status = 'running';

-- Každý průjezd zastávkou. Základ pro „Moji trasu“.
CREATE TABLE stop_events (
    service_date  DATE        NOT NULL,
    trip_id       VARCHAR(64) NOT NULL,
    stop_seq      SMALLINT    NOT NULL,
    route_id      VARCHAR(32) NOT NULL,
    stop_id       VARCHAR(32) NOT NULL,
    node_id       INTEGER     NOT NULL,      -- denormalizace pro rychlé hledání
    planned_arr   TIMESTAMPTZ,
    planned_dep   TIMESTAMPTZ,
    arr_delay     INTEGER,                   -- s, NULL = nezměřeno
    dep_delay     INTEGER,
    PRIMARY KEY (service_date, trip_id, stop_seq)
) PARTITION BY RANGE (service_date);
-- oddíly po měsících zakládá server dopředu, staré se mažou celé (DROP PARTITION)
CREATE INDEX idx_events_lookup ON stop_events (route_id, node_id, service_date);

-- Měsíční souhrn pro detail linky a dlouhodobou historii (počítá se v noci)
CREATE TABLE stop_route_hourly (
    month         DATE        NOT NULL,      -- první den měsíce
    route_id      VARCHAR(32) NOT NULL,
    node_id       INTEGER     NOT NULL,
    day_type      VARCHAR(16) NOT NULL,
    hour          SMALLINT    NOT NULL,
    dep_total     INTEGER     NOT NULL,
    dep_on_time   INTEGER     NOT NULL,
    dep_early     INTEGER     NOT NULL,
    dep_late      INTEGER     NOT NULL,
    dep_late_sum  BIGINT      NOT NULL,
    max_delay     INTEGER     NOT NULL,
    PRIMARY KEY (month, route_id, node_id, day_type, hour)
);

CREATE TABLE hourly_stats (
    service_date    DATE        NOT NULL,
    hour            SMALLINT    NOT NULL,   -- hodina plánovaného odjezdu, Europe/Prague
    category        VARCHAR(16) NOT NULL,
    dep_total       INTEGER     NOT NULL DEFAULT 0,
    dep_on_time     INTEGER     NOT NULL DEFAULT 0,
    dep_early       INTEGER     NOT NULL DEFAULT 0,
    dep_late        INTEGER     NOT NULL DEFAULT 0,
    dep_late_sum    BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (service_date, hour, category)
);

CREATE TABLE daily_route_stats (
    service_date   DATE        NOT NULL,
    route_id       VARCHAR(32) NOT NULL,
    trips_total    INTEGER     NOT NULL,
    trips_finished INTEGER     NOT NULL,
    trips_canceled INTEGER     NOT NULL,
    dep_total      INTEGER     NOT NULL DEFAULT 0,
    dep_on_time    INTEGER     NOT NULL DEFAULT 0,
    dep_early      INTEGER     NOT NULL DEFAULT 0,
    dep_late       INTEGER     NOT NULL DEFAULT 0,
    dep_late_sum   BIGINT      NOT NULL DEFAULT 0,
    sum_max_delay  BIGINT      NOT NULL DEFAULT 0,   -- COALESCE při výpočtu
    max_delay      INTEGER     NOT NULL DEFAULT 0,
    PRIMARY KEY (service_date, route_id)
);

CREATE TABLE daily_network_stats (
    service_date      DATE        NOT NULL,
    category          VARCHAR(16) NOT NULL,
    trips_total       INTEGER     NOT NULL,
    trips_finished    INTEGER     NOT NULL,
    trips_canceled    INTEGER     NOT NULL,
    dep_total         INTEGER     NOT NULL DEFAULT 0,
    dep_on_time       INTEGER     NOT NULL DEFAULT 0,
    dep_early         INTEGER     NOT NULL DEFAULT 0,
    dep_late          INTEGER     NOT NULL DEFAULT 0,
    dep_late_sum      BIGINT      NOT NULL DEFAULT 0,
    sum_max_delay     BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (service_date, category)
);

CREATE TABLE ingest_runs (
    id              BIGSERIAL   PRIMARY KEY,
    started_at      TIMESTAMPTZ NOT NULL,
    finished_at     TIMESTAMPTZ,
    ok              BOOLEAN     NOT NULL,
    vehicles        INTEGER,
    valid_snapshots INTEGER,
    error           TEXT
);
CREATE INDEX idx_ingest_started ON ingest_runs (started_at);
```

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
