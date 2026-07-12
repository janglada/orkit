-- 3D bin packing example database: a warehouse loading consumer electronics
-- onto a heterogeneous fleet of containers (air-freight ULDs, sea containers,
-- and a pallet). Each container has interior dimensions (length/width/height
-- in cm) and a max weight (kg). Each box is a real-world SKU with dimensions
-- and weight. The objective is to minimize the number of containers used.

DROP TABLE IF EXISTS container;
CREATE TABLE container (
    id          TEXT PRIMARY KEY,
    length      INTEGER,   -- cm
    width       INTEGER,   -- cm
    height      INTEGER,   -- cm
    max_weight  INTEGER    -- kg
);

DROP TABLE IF EXISTS box;
CREATE TABLE box (
    id      TEXT PRIMARY KEY,
    length  INTEGER,   -- cm
    width   INTEGER,   -- cm
    height  INTEGER,   -- cm
    weight  INTEGER    -- kg
);

-- Three containers: a large sea container, a medium air-freight ULD, and a small pallet.
INSERT INTO container (id, length, width, height, max_weight) VALUES
    ('SEA-40',  120, 100, 80,  500),
    ('ULD-AKE',  60,  50, 45,  150),
    ('PALLET',   40,  40, 30,   80);

-- 12 boxes: a mix of small electronics, medium appliances, and one large item.
INSERT INTO box (id, length, width, height, weight) VALUES
    ('PHONE-01',  8,   5,   3,   1),
    ('PHONE-02',  8,   5,   3,   1),
    ('PHONE-03',  8,   5,   3,   1),
    ('PHONE-04',  8,   5,   3,   1),
    ('TABLET-01', 15, 10,   2,   2),
    ('TABLET-02', 15, 10,   2,   2),
    ('LAPTOP-01', 25, 18,   4,   3),
    ('LAPTOP-02', 25, 18,   4,   3),
    ('MONITOR-1', 35, 22,   6,   5),
    ('MONITOR-2', 35, 22,   6,   5),
    ('SPEAKER-1', 20, 15,  10,   4),
    ('SERVER-01', 45, 35,  12,  18);

-- ──────────────────────────────────────────────────────────────────────────
-- Stacking-driven example: a small 3-box tower exercising rotation, support,
-- weight-bearing, and explicit precedence. All boxes are rotatable so every
-- placement carries a non-null orientation. BASE bears the load of everything
-- stacked above it (MID + TOP); MID bears TOP.
-- ──────────────────────────────────────────────────────────────────────────

DROP TABLE IF EXISTS stack_container;
CREATE TABLE stack_container (
    id          TEXT PRIMARY KEY,
    length      INTEGER,   -- cm
    width       INTEGER,   -- cm
    height      INTEGER,   -- cm
    max_weight  INTEGER    -- kg
);
INSERT INTO stack_container (id, length, width, height, max_weight) VALUES
    ('TOWER', 10, 10, 10, 100);

DROP TABLE IF EXISTS stack_box;
CREATE TABLE stack_box (
    id                  TEXT PRIMARY KEY,
    length              INTEGER,   -- cm
    width               INTEGER,   -- cm
    height              INTEGER,   -- cm
    weight              INTEGER,   -- kg
    rotatable           INTEGER NOT NULL DEFAULT 0,  -- 0 = fixed, 1 = rotatable
    max_stacked_weight  INTEGER    -- NULL = unbounded load capacity
);
INSERT INTO stack_box (id, length, width, height, weight, rotatable, max_stacked_weight) VALUES
    ('BASE', 10, 10, 2,  5, 1, 60),   -- large footprint base, bears up to 60 kg
    ('MID',   8,  6, 2, 10, 1, 40),   -- mid layer, bears up to 40 kg
    ('TOP',   4,  4, 2, 20, 1, NULL); -- top layer, unbounded load capacity

-- Explicit vertical order: BASE below MID, MID below TOP.
DROP TABLE IF EXISTS stack_precedence;
CREATE TABLE stack_precedence (
    box_id     TEXT,
    relation   TEXT,    -- 'above' or 'below'
    other_id   TEXT,
    PRIMARY KEY (box_id, relation, other_id)
);
INSERT INTO stack_precedence (box_id, relation, other_id) VALUES
    ('BASE', 'below', 'MID'),
    ('MID',  'below', 'TOP');