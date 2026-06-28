-- ─────────────────────────────────────────────────────────────────────────────
-- Last-mile parcel delivery — regional courier depot (Barcelona)
-- ─────────────────────────────────────────────────────────────────────────────
-- One depot (Mercabarna logistics hub, Zona Franca), a small heterogeneous fleet,
-- and 16 customer stops with demands, service times, and time windows.
--
-- open_min / close_min are minutes-from-midnight.
--   0 / 1440  means "no window" (flexible delivery any time of day).
--   Two cohorts are seeded: a morning wave (480–660 = 08:00–11:00) and a
--   midday wave (660–840 = 11:00–14:00); the rest are flexible.
--
-- Total demand (60) fits comfortably within total fleet capacity (90) so the
-- routing model is feasible with slack for the time-windowed cohorts.
-- ─────────────────────────────────────────────────────────────────────────────

-- ── Depot (single row) ────────────────────────────────────────────────────────
CREATE TABLE depot (
    id   TEXT PRIMARY KEY,
    name TEXT,
    lat  REAL NOT NULL,
    lon  REAL NOT NULL
);

-- ── Heterogeneous fleet ───────────────────────────────────────────────────────
CREATE TABLE vehicle (
    id             TEXT PRIMARY KEY,
    capacity       INTEGER NOT NULL,
    max_distance_m INTEGER NOT NULL
);

-- ── Customer stops ────────────────────────────────────────────────────────────
CREATE TABLE stop (
    id          TEXT PRIMARY KEY,
    name        TEXT,
    lat         REAL NOT NULL,
    lon         REAL NOT NULL,
    demand      INTEGER NOT NULL,
    service_min INTEGER NOT NULL,
    open_min    INTEGER NOT NULL,
    close_min   INTEGER NOT NULL
);

-- ── Seed: depot ───────────────────────────────────────────────────────────────
INSERT INTO depot (id, name, lat, lon) VALUES
    ('Mercabarna', 'Mercabarna Logistics Hub', 41.3473, 2.1260);

-- ── Seed: fleet — 2 large vans + 2 small vans (total capacity 90) ────────────
INSERT INTO vehicle (id, capacity, max_distance_m) VALUES
    ('LargeVan-1', 30, 60000),
    ('LargeVan-2', 30, 60000),
    ('SmallVan-1', 15, 50000),
    ('SmallVan-2', 15, 45000);

-- ── Seed: morning wave (08:00–11:00) — hotels and high-end drops ─────────────
INSERT INTO stop (id, name, lat, lon, demand, service_min, open_min, close_min) VALUES
    ('arts',       'Hotel Arts',          41.3858, 2.1968, 5, 15, 480, 660),
    ('wbarcelona', 'Hotel W Barcelona',   41.3736, 2.1884, 4, 15, 480, 660),
    ('mandarin',   'Hotel Mandarin',      41.3936, 2.1612, 3, 10, 480, 660),
    ('majestic',   'Hotel Majestic',      41.3923, 2.1651, 3, 10, 480, 660),
    ('claris',     'Hotel Claris',        41.3946, 2.1630, 3, 10, 480, 660);

-- ── Seed: midday wave (11:00–14:00) — supermarkets and caterers ──────────────
INSERT INTO stop (id, name, lat, lon, demand, service_min, open_min, close_min) VALUES
    ('caprabo',    'Caprabo Eixample',    41.3888, 2.1524, 7, 20, 660, 840),
    ('mercadona',  'Mercadona Gràcia',    41.4051, 2.1561, 6, 20, 660, 840),
    ('catering',   'Catering Poblenou',   41.4015, 2.1978, 5, 15, 660, 840),
    ('sants',      'Supermercado Sants',  41.3740, 2.1385, 4, 15, 660, 840);

-- ── Seed: flexible stops (no time window — delivered whenever convenient) ────
INSERT INTO stop (id, name, lat, lon, demand, service_min, open_min, close_min) VALUES
    ('barceloneta', 'Bodega Barceloneta',   41.3802, 2.1894, 3, 10, 0, 1440),
    ('raval',       'Bar Raval',            41.3793, 2.1644, 3,  8, 0, 1440),
    ('lescorts',    'Restaurante Les Corts',41.3844, 2.1317, 3, 10, 0, 1440),
    ('sarria',      'Café Sarrià',          41.4007, 2.1339, 2,  8, 0, 1440),
    ('santmarti',   'Mercat Sant Martí',    41.4001, 2.1967, 4, 12, 0, 1440),
    ('noubarris',   'Colmado Nou Barris',   41.4380, 2.1793, 3,  8, 0, 1440),
    ('horta',       'Pastisseria Horta',    41.4259, 2.1712, 2,  5, 0, 1440);
