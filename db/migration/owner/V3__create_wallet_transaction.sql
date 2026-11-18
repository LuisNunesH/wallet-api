CREATE TABLE wallet_transaction (
  id               NUMBER(19)                  DEFAULT seq_wallet_transaction.NEXTVAL NOT NULL,
  wallet_id        NUMBER(19)                  NOT NULL,
  idempotency_key  VARCHAR2(64)                NOT NULL,
  operation_type   VARCHAR2(6)                 NOT NULL,
  amount           NUMBER(15,2)                NOT NULL,
  balance_after    NUMBER(15,2)                NOT NULL,
  description      VARCHAR2(140),
  created_at       TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
  CONSTRAINT pk_wallet_transaction PRIMARY KEY (id),
  CONSTRAINT fk_wallet_transaction_wallet FOREIGN KEY (wallet_id) REFERENCES wallet (id),
  CONSTRAINT uk_wallet_transaction_idem UNIQUE (wallet_id, idempotency_key),
  CONSTRAINT ck_wallet_transaction_type CHECK (operation_type IN ('CREDIT', 'DEBIT')),
  CONSTRAINT ck_wallet_transaction_amount CHECK (amount > 0),
  CONSTRAINT ck_wallet_transaction_balance CHECK (balance_after >= 0)
);

CREATE INDEX ix_wallet_transaction_period ON wallet_transaction (wallet_id, created_at, id);

ALTER TABLE wallet ADD CONSTRAINT fk_wallet_last_transaction
  FOREIGN KEY (last_transaction_id) REFERENCES wallet_transaction (id);
