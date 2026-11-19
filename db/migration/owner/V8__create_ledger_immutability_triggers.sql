CREATE OR REPLACE TRIGGER trg_wallet_transaction_immutable
BEFORE UPDATE OR DELETE ON wallet_transaction
BEGIN
  RAISE_APPLICATION_ERROR(-20900, 'Lançamentos do extrato são imutáveis e não podem ser alterados ou apagados');
END;
/

CREATE OR REPLACE TRIGGER trg_protect_ledger_ddl
BEFORE TRUNCATE OR DROP ON SCHEMA
BEGIN
  IF ora_dict_obj_name = 'WALLET_TRANSACTION' THEN
    RAISE_APPLICATION_ERROR(-20900, 'O extrato não pode ser truncado nem removido');
  END IF;
END;
/
