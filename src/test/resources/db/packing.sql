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