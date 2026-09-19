-- Removes the legacy sample notes table. V1__init.sql has been retired;
-- fresh databases start at V2. This keeps databases created from the old
-- V1 migration clean.
DROP TABLE IF EXISTS notes;
