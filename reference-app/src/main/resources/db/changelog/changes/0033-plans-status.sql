--liquibase formatted sql

-- A plan's status: PLANNED until it is DONE, on a day the user gives, or CANCELLED; both are final, and a
-- cancelled plan frees its days. bootstrap-copy.sh copies this file with the example piece.

--changeset dev:0033-plans-status
-- Every plan already there was planned: the default fills those rows, then goes, since the code names the
-- status on every insert.
ALTER TABLE plans ADD COLUMN status text NOT NULL DEFAULT 'PLANNED';
ALTER TABLE plans ALTER COLUMN status DROP DEFAULT;
ALTER TABLE plans ADD COLUMN done_on date;
ALTER TABLE plans ADD CONSTRAINT ck_plans_status CHECK (status IN ('PLANNED', 'DONE', 'CANCELLED'));
-- A done plan has its day and only a done one has one, never before the plan's first day.
ALTER TABLE plans ADD CONSTRAINT ck_plans_done_on CHECK ((status = 'DONE') = (done_on IS NOT NULL) AND done_on >= starts_on);
--rollback ALTER TABLE plans DROP CONSTRAINT ck_plans_done_on;
--rollback ALTER TABLE plans DROP CONSTRAINT ck_plans_status;
--rollback ALTER TABLE plans DROP COLUMN done_on;
--rollback ALTER TABLE plans DROP COLUMN status;

--changeset dev:0033-plans-live-days
-- Only a plan that isn't CANCELLED holds its days: the overlap rule of 0011, for live plans.
ALTER TABLE plans DROP CONSTRAINT ex_plans_owner_id_dates;
-- tenancy-row:begin
-- ALTER TABLE plans ADD CONSTRAINT ex_plans_owner_id_dates
--     EXCLUDE USING gist (owner_id WITH =, tenant_id WITH =, daterange(starts_on, ends_on, '[]') WITH &&) WHERE (status <> 'CANCELLED');
-- --rollback ALTER TABLE plans DROP CONSTRAINT ex_plans_owner_id_dates;
-- --rollback ALTER TABLE plans ADD CONSTRAINT ex_plans_owner_id_dates EXCLUDE USING gist (owner_id WITH =, tenant_id WITH =, daterange(starts_on, ends_on, '[]') WITH &&);
-- tenancy-row:else
ALTER TABLE plans ADD CONSTRAINT ex_plans_owner_id_dates
    EXCLUDE USING gist (owner_id WITH =, daterange(starts_on, ends_on, '[]') WITH &&) WHERE (status <> 'CANCELLED');
--rollback ALTER TABLE plans DROP CONSTRAINT ex_plans_owner_id_dates;
--rollback ALTER TABLE plans ADD CONSTRAINT ex_plans_owner_id_dates EXCLUDE USING gist (owner_id WITH =, daterange(starts_on, ends_on, '[]') WITH &&);
-- tenancy-row:end
