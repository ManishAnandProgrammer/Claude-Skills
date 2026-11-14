--liquibase formatted sql

--changeset test:tenancy-probe
CREATE TABLE tenancy_probe (
    id    uuid NOT NULL,
    label text NOT NULL,
    CONSTRAINT pk_tenancy_probe PRIMARY KEY (id)
);
--rollback DROP TABLE tenancy_probe;
