-- PostgreSQL / openGauss: run in one session with the application's search_path.
-- Default: audit only. For repair, stop writers, reconnect database clients to
-- discard cached sequence values, then change repair_enabled below to TRUE.
-- Sequence changes made by setval are NOT rolled back by ROLLBACK.
BEGIN;

CREATE TEMP TABLE serial_sequence_options (repair_enabled BOOLEAN NOT NULL);
INSERT INTO serial_sequence_options VALUES (FALSE);

CREATE TEMP TABLE serial_sequence_report (
    table_name TEXT,
    column_name TEXT,
    sequence_name TEXT,
    max_id NUMERIC,
    last_value NUMERIC,
    is_called BOOLEAN,
    increment_by NUMERIC,
    status TEXT,
    detail TEXT
);

DO $$
DECLARE
    item RECORD;
    sequence_oid OID;
    sequence_label TEXT;
    table_max NUMERIC;
    sequence_last NUMERIC;
    sequence_called BOOLEAN;
    sequence_step NUMERIC;
    sequence_limit NUMERIC;
    sequence_cycles BOOLEAN;
    owner_count INTEGER;
    repair BOOLEAN;
    result_status TEXT;
BEGIN
    SELECT repair_enabled INTO repair FROM serial_sequence_options;

    -- Only visible application tables; an unqualified name resolves to this OID.
    -- Includes owned SERIAL and IDENTITY sequences. Unowned custom nextval
    -- defaults require a separate review and are deliberately not changed.
    FOR item IN
        SELECT c.oid AS table_oid, c.relname AS table_name,
               a.attname AS column_name,
               pg_get_serial_sequence(quote_ident(c.relname), a.attname) AS seq
        FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        JOIN pg_attribute a ON a.attrelid = c.oid
        WHERE c.relkind = 'r' AND a.attnum > 0 AND NOT a.attisdropped
          AND pg_table_is_visible(c.oid)
          AND n.nspname <> 'information_schema'
          AND n.nspname NOT LIKE 'pg_%'
        ORDER BY c.oid, a.attnum
    LOOP
        IF item.seq IS NULL THEN
            CONTINUE;
        END IF;
        BEGIN
            sequence_oid := CAST(item.seq AS regclass);
            SELECT quote_ident(relname) INTO sequence_label
            FROM pg_class WHERE oid = sequence_oid;
            IF CAST(CAST(sequence_oid AS regclass) AS TEXT) <> sequence_label THEN
                INSERT INTO serial_sequence_report VALUES
                    (item.table_name, item.column_name, item.seq, NULL, NULL,
                     NULL, NULL, 'SKIPPED', 'Sequence is not visible by its unqualified name');
                CONTINUE;
            END IF;

            -- Count defaults as well as ownership; do not reset a shared sequence
            -- from the maximum of just one of its consuming tables.
            SELECT COUNT(*) INTO owner_count FROM (
                SELECT d.refobjid AS relid, d.refobjsubid AS attnum
                FROM pg_depend d
                WHERE d.classid = 'pg_class'::regclass AND d.objid = sequence_oid
                  AND d.refclassid = 'pg_class'::regclass
                  AND d.deptype IN ('a', 'i') AND d.refobjsubid > 0
                UNION
                SELECT ad.adrelid, ad.adnum
                FROM pg_depend d JOIN pg_attrdef ad ON ad.oid = d.objid
                WHERE d.classid = 'pg_attrdef'::regclass
                  AND d.refclassid = 'pg_class'::regclass
                  AND d.refobjid = sequence_oid
            ) consumers;
            IF owner_count <> 1 THEN
                INSERT INTO serial_sequence_report VALUES
                    (item.table_name, item.column_name, sequence_label, NULL,
                     NULL, NULL, NULL, 'SKIPPED', 'Shared or ambiguous sequence');
                CONTINUE;
            END IF;

            IF repair THEN
                EXECUTE 'LOCK TABLE ' || quote_ident(item.table_name)
                    || ' IN SHARE ROW EXCLUSIVE MODE';
            END IF;
            EXECUTE 'SELECT MAX(' || quote_ident(item.column_name) || ') FROM '
                || quote_ident(item.table_name) INTO table_max;
            EXECUTE 'SELECT last_value, is_called FROM ' || sequence_label
                INTO sequence_last, sequence_called;

            -- PostgreSQL 10+ moved parameters out of the sequence relation.
            IF EXISTS (SELECT 1 FROM pg_class c JOIN pg_namespace n
                       ON n.oid = c.relnamespace
                       WHERE n.nspname = 'pg_catalog' AND c.relname = 'pg_sequence') THEN
                EXECUTE 'SELECT seqincrement, seqmax, seqcycle FROM pg_catalog.pg_sequence WHERE seqrelid = '
                    || sequence_oid INTO sequence_step, sequence_limit, sequence_cycles;
            ELSE
                EXECUTE 'SELECT increment_by, max_value, is_cycled FROM '
                    || sequence_label INTO sequence_step, sequence_limit, sequence_cycles;
            END IF;

            result_status := 'OK';
            IF sequence_step IS NULL OR sequence_limit IS NULL
               OR sequence_cycles IS NULL OR sequence_step <= 0 OR sequence_cycles THEN
                result_status := 'SKIPPED';
            ELSIF (CASE WHEN sequence_called THEN sequence_last + sequence_step
                        ELSE sequence_last END) > sequence_limit
                  OR (table_max IS NOT NULL AND table_max + sequence_step > sequence_limit) THEN
                result_status := 'EXHAUSTED';
            ELSIF table_max IS NOT NULL AND
                  (CASE WHEN sequence_called THEN sequence_last + sequence_step
                        ELSE sequence_last END) <= table_max THEN
                result_status := 'BEHIND';
                IF repair THEN
                    PERFORM setval(CAST(sequence_oid AS regclass), CAST(table_max AS BIGINT), TRUE);
                    result_status := 'REPAIRED';
                END IF;
            END IF;
            INSERT INTO serial_sequence_report VALUES
                (item.table_name, item.column_name, sequence_label, table_max,
                 sequence_last, sequence_called, sequence_step, result_status,
                 CASE WHEN result_status = 'SKIPPED' THEN 'Descending, cycling or unknown sequence parameters'
                      WHEN result_status = 'EXHAUSTED' THEN 'ID range exhausted; manual migration required'
                      ELSE NULL END);
        EXCEPTION WHEN OTHERS THEN
            INSERT INTO serial_sequence_report VALUES
                (item.table_name, item.column_name, item.seq, NULL, NULL, NULL,
                 NULL, 'ERROR', SQLSTATE || ': ' || SQLERRM);
        END;
    END LOOP;
END $$;

SELECT * FROM serial_sequence_report ORDER BY status, table_name, column_name;
DROP TABLE serial_sequence_report;
DROP TABLE serial_sequence_options;
COMMIT;
