CREATE TABLE wallet (
  id                   NUMBER(19)                  DEFAULT seq_wallet.NEXTVAL NOT NULL,
  holder_name          VARCHAR2(150)               NOT NULL,
  holder_document      VARCHAR2(14),
  balance              NUMBER(15,2)                DEFAULT 0 NOT NULL,
  last_transaction_id  NUMBER(19),
  created_at           TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
  updated_at           TIMESTAMP(6) WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
  CONSTRAINT pk_wallet PRIMARY KEY (id),
  CONSTRAINT ck_wallet_balance_non_negative CHECK (balance >= 0),
  CONSTRAINT ck_wallet_holder_name CHECK (TRIM(holder_name) IS NOT NULL),
  CONSTRAINT ck_wallet_holder_document CHECK (
    holder_document IS NULL
    OR REGEXP_LIKE(holder_document, '^([0-9]{11}|[0-9]{14})$')
  )
);
