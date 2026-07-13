-- 2D non-guillotine cutting-stock example database: a sheet-metal fabricator
-- cutting rectangular blanks from stock sheets. Two sheet types with different
-- dimensions and costs, and three blank types with varying quantities. The
-- objective is to minimize total area consumed (MinimizeWaste), which is
-- equivalent to minimizing total area waste.

DROP TABLE IF EXISTS stock;
CREATE TABLE stock (
    id        TEXT PRIMARY KEY,
    length    INTEGER,   -- mm (X extent)
    width     INTEGER,   -- mm (Y extent)
    quantity  INTEGER,   -- how many sheets of this type are available
    cost      INTEGER    -- cost per sheet (arbitrary units)
);

DROP TABLE IF EXISTS piece;
CREATE TABLE piece (
    id        TEXT PRIMARY KEY,
    length    INTEGER,   -- mm (X extent)
    width     INTEGER,   -- mm (Y extent)
    quantity  INTEGER    -- how many blanks of this type are needed
);

-- Two sheet types: standard 100×80 sheets (cheaper) and large 200×150 sheets.
INSERT INTO stock (id, length, width, quantity, cost) VALUES
    ('STD-100x80',  100,  80, 4, 50),
    ('BIG-200x150', 200, 150, 2, 120);

-- Blanks: a mix of small and medium rectangular pieces.
INSERT INTO piece (id, length, width, quantity) VALUES
    ('BLK-60x40', 60, 40, 4),
    ('BLK-50x30', 50, 30, 3),
    ('BLK-30x20', 30, 20, 5);
