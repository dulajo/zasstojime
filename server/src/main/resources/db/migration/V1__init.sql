-- Schéma Fáze 1 (docs/technicke.md – Datový model).
-- Bloky mezi [jooq ignore start/stop] jsou syntaxe, kterou generátor jOOQ (DDLDatabase) nepotřebuje.

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
    node_id       INTEGER,                   -- asw_node_id = stanice; NULL = technický bod (ADR-0045)
    platform_code VARCHAR(8),
    lat           DOUBLE PRECISION,
    lon           DOUBLE PRECISION,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_stops_node ON stops (node_id);

CREATE TABLE gtfs_trips (
    trip_id           VARCHAR(64) PRIMARY KEY,
    route_id          VARCHAR(32) NOT NULL,
    first_departure_s INTEGER     NOT NULL,   -- výjezd z první zastávky v s od půlnoci, může být ≥ 86 400 (ADR-0050)
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE calendar_days (
    day           DATE        PRIMARY KEY,
    day_type      VARCHAR(16) NOT NULL CHECK (day_type IN ('workday','saturday','sunday_holiday')),
    holiday_name  TEXT
);

CREATE TABLE trips_daily (
    service_date       DATE        NOT NULL,
    trip_id            VARCHAR(64) NOT NULL,
    route_id           VARCHAR(32) NOT NULL,
    route_short        VARCHAR(16) NOT NULL,
    category           VARCHAR(16) NOT NULL,
    headsign           TEXT,
    vehicle_id         VARCHAR(32),
    trip_start_at      TIMESTAMPTZ,
    first_seen_at      TIMESTAMPTZ NOT NULL,
    last_seen_at       TIMESTAMPTZ NOT NULL,
    status             VARCHAR(12) NOT NULL CHECK (status IN ('running','finished','canceled')),
    finished_by        VARCHAR(12) CHECK (finished_by IN ('after_track','timeout')),
    finished_at        TIMESTAMPTZ,            -- pro znovuotevření po restartu (ADR-0042)
    last_delay         INTEGER,
    max_delay          INTEGER,
    max_delay_at       TIMESTAMPTZ,
    max_delay_stop     VARCHAR(32),            -- GTFS stop_id
    last_stop_seq      INTEGER,                -- poslední uzavřená zastávka
    dep_total          INTEGER     NOT NULL DEFAULT 0,
    dep_on_time        INTEGER     NOT NULL DEFAULT 0,
    dep_early          INTEGER     NOT NULL DEFAULT 0,
    dep_late           INTEGER     NOT NULL DEFAULT 0,
    dep_late_sum       BIGINT      NOT NULL DEFAULT 0,
    valid_snapshots    INTEGER     NOT NULL DEFAULT 0,
    last_valid_at      TIMESTAMPTZ,            -- timeout po restartu
    last_origin_at     TIMESTAMPTZ,            -- deduplikace po restartu
    last_dep_origin_at TIMESTAMPTZ,            -- kontrola nereálného skoku po restartu (ADR-0035)
    flagged            BOOLEAN     NOT NULL DEFAULT false,   -- max_delay > ALERT_DELAY_S (ADR-0055)
    verified           BOOLEAN     NOT NULL DEFAULT false,   -- správce potvrdil
    excluded           BOOLEAN     NOT NULL DEFAULT false,   -- správce vyřadil z rekordmana
    PRIMARY KEY (service_date, trip_id),
    CHECK (dep_total = dep_on_time + dep_early + dep_late),
    CHECK (dep_on_time >= 0 AND dep_early >= 0 AND dep_late >= 0 AND dep_late_sum >= 0)
);
CREATE INDEX idx_trips_route   ON trips_daily (service_date, route_id);
CREATE INDEX idx_trips_running ON trips_daily (status) WHERE status = 'running';

-- Každý průjezd zastávkou. Základ pro „Moji trasu“ (ADR-0044).
-- Oddíly po měsících (stop_events_yYYYYmMM) zakládá server dopředu, staré maže celé.
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
)
/* [jooq ignore start] */ PARTITION BY RANGE (service_date) /* [jooq ignore stop] */;
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
    sum_max_delay  BIGINT      NOT NULL DEFAULT 0,   -- SUM(GREATEST(0, max_delay))
    max_delay      INTEGER     NOT NULL DEFAULT 0,
    PRIMARY KEY (service_date, route_id)
);

CREATE TABLE daily_network_stats (
    service_date      DATE        NOT NULL,
    category          VARCHAR(16) NOT NULL,   -- 'all' = celá síť
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
    stop_events     INTEGER,                -- nové nebo změněné průjezdy v cyklu
    error           TEXT
);
CREATE INDEX idx_ingest_started ON ingest_runs (started_at);
