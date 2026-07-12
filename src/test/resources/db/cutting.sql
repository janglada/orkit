-- 1D cutting-stock example database: a steel mill cutting customer orders
-- from stock rolls. Two stock types with different costs (standard vs premium
-- grade) and three piece types with varying lengths and quantities. The
-- objective is to minimize total cost (using the cheaper stock when possible).

DROP TABLE IF EXISTS stock;
CREATE TABLE stock (
    id        TEXT PRIMARY KEY,
    length    INTEGER,   -- mm
    quantity  INTEGER,   -- how many rolls of this type are available
    cost      INTEGER    -- cost per roll (arbitrary units)
);

DROP TABLE IF EXISTS piece;
CREATE TABLE piece (
    id        TEXT PRIMARY KEY,
    length    INTEGER,   -- mm
    quantity  INTEGER    -- how many pieces of this type are needed
);

-- Two stock types: 1000mm rolls. Standard-grade costs 10/roll; premium costs 25/roll.
-- MinimizeTotalCost should prefer the standard rolls.
INSERT INTO stock (id, length, quantity, cost) VALUES
    ('STD-1000', 1000, 5, 10),
    ('PRM-1000', 1000, 5, 25);

-- 10 pieces: a mix of short, medium, and long cuts.
INSERT INTO piece (id, length, quantity) VALUES
    ('P-200', 200, 3),
    ('P-300', 300, 2),
    ('P-400', 400, 2),
    ('P-150', 150, 3);