--liquibase formatted sql

--changeset dev:0100-expense-claims
CREATE TABLE expense_claims (
    id               uuid          NOT NULL,
    owner_id         uuid          NOT NULL,
    approver_id      uuid          NOT NULL,
    amount           numeric(12,2) NOT NULL,
    currency         text          NOT NULL,
    spent_on         date          NOT NULL,
    description      text          NOT NULL,
    status           text          NOT NULL,
    decided_at       timestamptz,
    rejection_reason text,
    created_at       timestamptz   NOT NULL,
    updated_at       timestamptz   NOT NULL,
    created_by       uuid,
    updated_by       uuid,
    version          bigint        NOT NULL,
    CONSTRAINT pk_expense_claims PRIMARY KEY (id),
    CONSTRAINT fk_expense_claims_owner_id FOREIGN KEY (owner_id) REFERENCES users (id),
    CONSTRAINT fk_expense_claims_approver_id FOREIGN KEY (approver_id) REFERENCES users (id),
    CONSTRAINT ck_expense_claims_approver CHECK (approver_id <> owner_id),
    CONSTRAINT ck_expense_claims_amount CHECK (amount > 0),
    CONSTRAINT ck_expense_claims_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_expense_claims_status CHECK (status IN ('DRAFT', 'SUBMITTED', 'APPROVED', 'REJECTED')),
    CONSTRAINT ck_expense_claims_decided CHECK ((status IN ('APPROVED', 'REJECTED')) = (decided_at IS NOT NULL)),
    CONSTRAINT ck_expense_claims_rejection CHECK ((status = 'REJECTED') = (rejection_reason IS NOT NULL))
);
CREATE INDEX idx_expense_claims_owner_id_id ON expense_claims (owner_id, id);
CREATE INDEX idx_expense_claims_approver_id_id ON expense_claims (approver_id, id);
--rollback DROP TABLE expense_claims;
