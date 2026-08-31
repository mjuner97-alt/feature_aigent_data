ALTER TABLE skill_job
    ADD COLUMN  schedule_rules VARCHAR(256);

ALTER TABLE skill_flow
    ADD COLUMN  schedule_rules VARCHAR(256);