--liquibase formatted sql

--changeset dev:0100-expenses
CREATE TABLE expenses (
    id          uuid          NOT NULL,
    owner_id    uuid          NOT NULL,
    amount      numeric(12,2) NOT NULL,
    currency    text          NOT NULL,
    spent_on    date          NOT NULL,
    description text          NOT NULL,
    status      text          NOT NULL,
    created_at  timestamptz   NOT NULL,
    updated_at  timestamptz   NOT NULL,
    created_by  uuid,
    updated_by  uuid,
    version     bigint        NOT NULL,
    CONSTRAINT pk_expenses PRIMARY KEY (id),
    CONSTRAINT fk_expenses_owner_id FOREIGN KEY (owner_id) REFERENCES users (id),
    CONSTRAINT ck_expenses_amount CHECK (amount > 0),
    CONSTRAINT ck_expenses_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_expenses_status CHECK (status IN ('DRAFT', 'SUBMITTED', 'WITHDRAWN'))
);
CREATE INDEX idx_expenses_owner_id_id ON expenses (owner_id, id);
--rollback DROP TABLE expenses;
