-- Appointment-booking example database: a medical clinic with two doctors
-- (one generalist, one specialist) and a shared procedure room, operating
-- on a 2-day horizon (Mon 2026-07-13 – Tue 2026-07-14). Each resource has
-- one availability slot per day; the procedure room is available 24/7.
-- Appointments declare a duration, candidate resources, optional time
-- windows, and an unscheduled penalty. The objective is to maximize the
-- number of scheduled appointments (or minimize unscheduled penalty).

DROP TABLE IF EXISTS horizon;
CREATE TABLE horizon (
    start_date  TEXT,      -- 'YYYY-MM-DD'
    days        INTEGER
);

DROP TABLE IF EXISTS resource;
CREATE TABLE resource (
    id          TEXT PRIMARY KEY,
    slot_start  TEXT,      -- 'HH:MM' (NULL = 24/7)
    slot_end    TEXT       -- 'HH:MM' (NULL = 24/7)
);

DROP TABLE IF EXISTS appointment;
CREATE TABLE appointment (
    id              TEXT PRIMARY KEY,
    duration_min    INTEGER,
    resource_ids    TEXT,       -- comma-separated
    earliest        TEXT,       -- 'YYYY-MM-DD HH:MM' or NULL
    latest          TEXT,       -- 'YYYY-MM-DD HH:MM' or NULL
    penalty         INTEGER
);

INSERT INTO horizon (start_date, days) VALUES ('2026-07-13', 2);

-- Two doctors and a procedure room. Dr. Lee works 08:00–16:00; Dr. Patel
-- works 09:00–17:00; the procedure room is open 24/7.
INSERT INTO resource (id, slot_start, slot_end) VALUES
    ('DR-LEE',       '08:00', '16:00'),
    ('DR-PATEL',     '09:00', '17:00'),
    ('PROC-ROOM',    NULL,    NULL);

-- 8 appointments: a mix of short consultations, long procedures, and
-- one urgent case that must be scheduled on day 1 (earliest+latest window).
INSERT INTO appointment (id, duration_min, resource_ids, earliest, latest, penalty) VALUES
    ('CONSULT-A',   30,  'DR-LEE,DR-PATEL',          NULL,                  NULL,                    1),
    ('CONSULT-B',   30,  'DR-LEE,DR-PATEL',          NULL,                  NULL,                    1),
    ('CONSULT-C',   45,  'DR-LEE,DR-PATEL',          NULL,                  NULL,                    1),
    ('CONSULT-D',   30,  'DR-LEE,DR-PATEL',          NULL,                  NULL,                    1),
    ('PROCEDURE-1', 90,  'PROC-ROOM',                NULL,                  NULL,                    5),
    ('PROCEDURE-2', 60,  'PROC-ROOM',                NULL,                  NULL,                    5),
    ('URGENT-01',   60,  'DR-LEE,DR-PATEL',          '2026-07-13 08:00',   '2026-07-13 12:00',    100),
    ('FOLLOWUP-01', 20,  'DR-LEE,DR-PATEL',          '2026-07-14 14:00',   '2026-07-14 16:00',      1);