-- Database-driven workforce-staffing scenario for the Scheduling solver.
--
-- A small hospital ICU ward is staffed for one week (Mon 2026-07-06 .. Sun 2026-07-12).
-- Nurses carry skill tiers (RN, ICU); three shift templates (DAY / EVENING / NIGHT)
-- are instantiated on concrete dates with per-date coverage needs. Night shifts
-- require the ICU skill, so only senior nurses may cover them.
--
-- Loaded by SchedulingDbExampleTest via TestDatabase.load("db/scheduling.sql").

-- ── Schema ──────────────────────────────────────────────────────────────────

CREATE TABLE employee (
    id                  TEXT PRIMARY KEY,
    name                TEXT NOT NULL,
    skills              TEXT NOT NULL,              -- CSV e.g. 'RN,ICU'
    cost_per_hour       INTEGER NOT NULL,
    max_hours_per_week  INTEGER NOT NULL
);

-- Day-of-week availability. If an employee has NO rows here, they are available
-- all 7 days. Part-time / limited-availability staff carry explicit rows.
CREATE TABLE employee_available_day (
    employee_id  TEXT NOT NULL,
    day_of_week  TEXT NOT NULL,                    -- MON..SUN
    PRIMARY KEY (employee_id, day_of_week)
);

-- Per-employee shift preferences (soft). shift_id matches a shift_instance.id.
CREATE TABLE employee_preference (
    employee_id  TEXT NOT NULL,
    shift_id     TEXT NOT NULL,
    PRIMARY KEY (employee_id, shift_id)
);

-- Reusable shift templates: times, required skill, default coverage.
CREATE TABLE shift_template (
    code           TEXT PRIMARY KEY,               -- DAY / EVENING / NIGHT
    start_time     TEXT NOT NULL,                  -- 'HH:MM'
    end_time       TEXT NOT NULL,                  -- 'HH:MM'
    skill          TEXT NOT NULL,
    default_needs  INTEGER NOT NULL
);

-- Concrete shift occurrences on specific dates. `needs` overrides the template
-- default, allowing busier days to demand extra coverage.
CREATE TABLE shift_instance (
    id          TEXT PRIMARY KEY,                  -- e.g. 'Mon-Day'
    shift_date  TEXT NOT NULL,                     -- 'YYYY-MM-DD'
    shift_code  TEXT NOT NULL,                     -- FK -> shift_template.code
    needs       INTEGER NOT NULL
);

-- ── Seed: employees ─────────────────────────────────────────────────────────
-- 4 senior ICU+RN nurses, 5 staff RN nurses, 2 part-time RN nurses = 11 total.

INSERT INTO employee (id, name, skills, cost_per_hour, max_hours_per_week) VALUES
    ('e01', 'Dr. Alice',   'RN,ICU', 58, 40),
    ('e02', 'Dr. Bob',     'RN,ICU', 55, 40),
    ('e03', 'Dr. Carol',   'RN,ICU', 56, 40),
    ('e04', 'Dr. Dave',    'RN,ICU', 54, 36),
    ('e05', 'Nurse Eve',   'RN',     42, 40),
    ('e06', 'Nurse Frank', 'RN',     40, 40),
    ('e07', 'Nurse Grace', 'RN',     38, 36),
    ('e08', 'Nurse Hank',  'RN',     39, 40),
    ('e09', 'Nurse Iris',  'RN',     41, 40),
    ('e10', 'PT Jane',     'RN',     44, 24),
    ('e11', 'PT Kevin',    'RN',     43, 24);

-- Part-time availability: Jane works weekdays only; Kevin works Mon/Wed/Fri.
-- Everyone else has no rows → available all 7 days.

INSERT INTO employee_available_day (employee_id, day_of_week) VALUES
    ('e10', 'MON'), ('e10', 'TUE'), ('e10', 'WED'), ('e10', 'THU'), ('e10', 'FRI'),
    ('e11', 'MON'), ('e11', 'WED'), ('e11', 'FRI');

-- A few shift preferences (soft goals).

INSERT INTO employee_preference (employee_id, shift_id) VALUES
    ('e01', 'Mon-Day'),
    ('e06', 'Mon-Day'),
    ('e03', 'Tue-Night');

-- ── Seed: shift templates ───────────────────────────────────────────────────
-- DAY 07:00-15:00 (RN, 2), EVENING 15:00-23:00 (RN, 2), NIGHT 23:00-07:00 (ICU, 1).

INSERT INTO shift_template (code, start_time, end_time, skill, default_needs) VALUES
    ('DAY',     '07:00', '15:00', 'RN',  2),
    ('EVENING', '15:00', '23:00', 'RN',  2),
    ('NIGHT',   '23:00', '07:00', 'ICU', 1);

-- ── Seed: shift instances (Mon..Sun × 3 templates = 21) ─────────────────────
-- Week starts Monday 2026-07-06. Friday evening is busier (needs 3) to show the
-- per-date override; all others use the template default.

INSERT INTO shift_instance (id, shift_date, shift_code, needs) VALUES
    ('Mon-Day',     '2026-07-06', 'DAY',     2),
    ('Mon-Evening', '2026-07-06', 'EVENING', 2),
    ('Mon-Night',   '2026-07-06', 'NIGHT',   1),
    ('Tue-Day',     '2026-07-07', 'DAY',     2),
    ('Tue-Evening', '2026-07-07', 'EVENING', 2),
    ('Tue-Night',   '2026-07-07', 'NIGHT',   1),
    ('Wed-Day',     '2026-07-08', 'DAY',     2),
    ('Wed-Evening', '2026-07-08', 'EVENING', 2),
    ('Wed-Night',   '2026-07-08', 'NIGHT',   1),
    ('Thu-Day',     '2026-07-09', 'DAY',     2),
    ('Thu-Evening', '2026-07-09', 'EVENING', 2),
    ('Thu-Night',   '2026-07-09', 'NIGHT',   1),
    ('Fri-Day',     '2026-07-10', 'DAY',     2),
    ('Fri-Evening', '2026-07-10', 'EVENING', 3),
    ('Fri-Night',   '2026-07-10', 'NIGHT',   1),
    ('Sat-Day',     '2026-07-11', 'DAY',     2),
    ('Sat-Evening', '2026-07-11', 'EVENING', 2),
    ('Sat-Night',   '2026-07-11', 'NIGHT',   1),
    ('Sun-Day',     '2026-07-12', 'DAY',     2),
    ('Sun-Evening', '2026-07-12', 'EVENING', 2),
    ('Sun-Night',   '2026-07-12', 'NIGHT',   1);
