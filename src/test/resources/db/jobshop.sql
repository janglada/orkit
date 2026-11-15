-- ============================================================================
--  Custom furniture & cabinetry workshop — job-shop schema + seed data
-- ============================================================================
--  A realistic manufacturing schema for a small workshop producing made-to-
--  order furniture. Machines are grouped so that identical machines (here the
--  two CNC routers) form a flexible pool; routing steps either fix a specific
--  machine (machine_id) or target a whole group (machine_group) so the solver
--  picks any free member.
--
--  Loaded by io.github.janglada.orkit.jobshop.JobShopDbExampleTest via io.github.janglada.orkit.db.TestDatabase into an
--  in-memory SQLite database; no file is written to disk.
-- ============================================================================

-- ── Machines ────────────────────────────────────────────────────────────────
--  machine_group is NULL for singleton machines; identical machines share a
--  group name (e.g. CNC1 & CNC2 → group 'CNC') so routing steps can target
--  the group rather than a specific unit.
DROP TABLE IF EXISTS machine;
CREATE TABLE machine (
    id            TEXT PRIMARY KEY,
    name          TEXT NOT NULL,
    machine_group TEXT                                   -- nullable; NULL = singleton
);

INSERT INTO machine (id, name, machine_group) VALUES
    ('SAW',   'Panel Saw',          NULL),
    ('CNC1',  'CNC Router 1',       'CNC'),
    ('CNC2',  'CNC Router 2',       'CNC'),
    ('EDGE',  'Edge Bander',        NULL),
    ('SAND',  'Wide Belt Sander',   NULL),
    ('PAINT', 'Spray Booth',        NULL),
    ('ASM',   'Assembly Bench',     NULL),
    ('PACK',  'Packing Station',    NULL);

-- ── Products ────────────────────────────────────────────────────────────────
DROP TABLE IF EXISTS product;
CREATE TABLE product (
    id   TEXT PRIMARY KEY,
    name TEXT NOT NULL
);

INSERT INTO product (id, name) VALUES
    ('CABINET', 'Kitchen Cabinet'),
    ('TABLE',   'Dining Table'),
    ('SHELF',   'Wall Shelf'),
    ('DOOR',    'Wardrobe Door');

-- ── Routing steps ───────────────────────────────────────────────────────────
--  Exactly one of machine_group / machine_id is set per step:
--    * machine_group set → flexible: run on ANY machine in that group
--    * machine_id    set → fixed:    run on that specific machine
--  step_no is 1-based and orders the operations within a product's routing.
DROP TABLE IF EXISTS routing_step;
CREATE TABLE routing_step (
    product_id   TEXT    NOT NULL,
    step_no      INTEGER NOT NULL,
    machine_group TEXT,                                   -- nullable; set ⇒ flexible
    machine_id    TEXT,                                   -- nullable; set ⇒ fixed
    duration_min INTEGER NOT NULL,
    PRIMARY KEY (product_id, step_no),
    FOREIGN KEY (product_id) REFERENCES product(id)
);

-- CABINET: SAW → CNC(flexible) → EDGE → SAND → PAINT → ASM → PACK
INSERT INTO routing_step (product_id, step_no, machine_group, machine_id, duration_min) VALUES
    ('CABINET', 1, NULL,    'SAW',   15),
    ('CABINET', 2, 'CNC',   NULL,    45),
    ('CABINET', 3, NULL,    'EDGE',  20),
    ('CABINET', 4, NULL,    'SAND',  15),
    ('CABINET', 5, NULL,    'PAINT', 30),
    ('CABINET', 6, NULL,    'ASM',   40),
    ('CABINET', 7, NULL,    'PACK',  10);

-- TABLE: SAW → CNC(flexible) → SAND → ASM → PACK
INSERT INTO routing_step (product_id, step_no, machine_group, machine_id, duration_min) VALUES
    ('TABLE', 1, NULL,    'SAW',   10),
    ('TABLE', 2, 'CNC',   NULL,    30),
    ('TABLE', 3, NULL,    'SAND',  10),
    ('TABLE', 4, NULL,    'ASM',   25),
    ('TABLE', 5, NULL,    'PACK',  8);

-- SHELF: SAW → EDGE → SAND → PACK  (no CNC — a simpler flat-pack product)
INSERT INTO routing_step (product_id, step_no, machine_group, machine_id, duration_min) VALUES
    ('SHELF', 1, NULL, 'SAW',  8),
    ('SHELF', 2, NULL, 'EDGE', 12),
    ('SHELF', 3, NULL, 'SAND', 8),
    ('SHELF', 4, NULL, 'PACK', 6);

-- DOOR: SAW → CNC(flexible) → SAND → PAINT → PACK
INSERT INTO routing_step (product_id, step_no, machine_group, machine_id, duration_min) VALUES
    ('DOOR', 1, NULL,    'SAW',   12),
    ('DOOR', 2, 'CNC',   NULL,    35),
    ('DOOR', 3, NULL,    'SAND',  10),
    ('DOOR', 4, NULL,    'PAINT', 25),
    ('DOOR', 5, NULL,    'PACK',  8);

-- ── Work orders ─────────────────────────────────────────────────────────────
--  Concrete production orders. qty is recorded for realism (the model treats
--  each order as one job; a multi-unit order would expand into N jobs).
--    * release_min — hard earliest start for the order's first operation
--      (nullable ⇒ unconstrained). Applied via OperationConfig.releaseAt(...).
--    * due_min    — soft due date for the order's last operation, penalized as
--      tardiness under a tardiness-including goal. Applied via
--      OperationConfig.dueBy(...). Nullable ⇒ no due date.
DROP TABLE IF EXISTS work_order;
CREATE TABLE work_order (
    order_no    TEXT    PRIMARY KEY,
    product_id  TEXT    NOT NULL,
    qty         INTEGER NOT NULL DEFAULT 1,
    release_min INTEGER,                                     -- nullable; hard earliest start of op 0
    due_min     INTEGER,                                     -- nullable; soft due date of the last op
    FOREIGN KEY (product_id) REFERENCES product(id)
);

INSERT INTO work_order (order_no, product_id, qty, release_min, due_min) VALUES
    ('WO-1001', 'CABINET', 1, NULL, 240),
    ('WO-1002', 'TABLE',   1, 30,  150),
    ('WO-1003', 'SHELF',   1, NULL, NULL),
    ('WO-1004', 'DOOR',    1, 10,  180),
    ('WO-1005', 'CABINET', 1, 40,  300),
    ('WO-1006', 'TABLE',   1, 50,  NULL),
    ('WO-1007', 'SHELF',   1, NULL, 120);
