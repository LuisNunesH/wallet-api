CREATE SEQUENCE seq_balance_audit
  START WITH 1
  INCREMENT BY 1
  CACHE 50
  NOCYCLE;

CREATE TABLE balance_audit (
  id                NUMBER(19)                  DEFAULT seq_balance_audit.NEXTVAL NOT NULL,
  wallet_id         NUMBER(19)                  NOT NULL,
  transaction_id    NUMBER(19),
  previous_balance  NUMBER(15,2)                NOT NULL,
  new_balance       NUMBER(15,2)                NOT NULL,
  change_source     VARCHAR2(10)                NOT NULL,
  db_user           VARCHAR2(128)               DEFAULT SYS_CONTEXT('USERENV', 'SESSION_USER') NOT NULL,
  changed_at        TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
  CONSTRAINT pk_balance_audit PRIMARY KEY (id),
  CONSTRAINT ck_balance_audit_source CHECK (change_source IN ('PACKAGE', 'DIRECT')),
  CONSTRAINT ck_balance_audit_tx_source CHECK (
    (change_source = 'PACKAGE' AND transaction_id IS NOT NULL)
    OR change_source = 'DIRECT'
  )
);

CREATE INDEX ix_balance_audit_wallet ON balance_audit (wallet_id, id);
