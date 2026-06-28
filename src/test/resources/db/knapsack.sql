-- Multiple-knapsack example database: a freight-forwarder loading cargo onto
-- outbound trucks/containers. Each container has a weight capacity (in tons);
-- each cargo lot has a declared value (profit) and weight. The objective is to
-- maximize total shipped value across all containers.

-- Containers available for this dispatch.
DROP TABLE IF EXISTS bin;
CREATE TABLE bin (
    id        TEXT PRIMARY KEY,   -- container id, e.g. TRK-01
    capacity  INTEGER             -- max weight in tons
);

-- Cargo lots awaiting shipment.
DROP TABLE IF EXISTS item;
CREATE TABLE item (
    id      TEXT PRIMARY KEY,     -- lot id
    profit  INTEGER,              -- declared value (revenue if shipped)
    weight  INTEGER               -- weight in tons
);

INSERT INTO bin (id, capacity) VALUES
    ('TRK-01', 20),
    ('TRK-02', 20),
    ('TRK-03', 15);

INSERT INTO item (id, profit, weight) VALUES
    ('LOT-steel-coils',   60,  12),
    ('LOT-electronics',   90,   6),
    ('LOT-machinery',    120,  10),
    ('LOT-textiles',      40,   5),
    ('LOT-chemicals',     70,   8),
    ('LOT-furniture',     50,   7),
    ('LOT-foodstuff',     30,   4),
    ('LOT-oversize-rig', 150,  18),
    ('LOT-pharma',        80,   3),
    ('LOT-paper',         25,   9);
