--liquibase formatted sql

-- The staff's tenant list by NAME, alone and filtered by status: one index per offered order and per
-- offered equality filter, the id last (house-review DATA-10). NEWEST has its pair since 0030.

--changeset dev:0032-tenants-name-indexes
CREATE INDEX idx_tenants_name_id ON tenants (name, id);
CREATE INDEX idx_tenants_status_name_id ON tenants (status, name, id);
--rollback DROP INDEX idx_tenants_name_id, idx_tenants_status_name_id;
