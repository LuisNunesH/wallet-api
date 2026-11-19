CREATE OR REPLACE TRIGGER trg_wallet_balance_audit
AFTER UPDATE OF balance ON wallet
FOR EACH ROW
WHEN (NEW.balance <> OLD.balance)
DECLARE
  v_matches PLS_INTEGER := 0;
BEGIN
  IF :NEW.last_transaction_id IS NOT NULL
     AND (:OLD.last_transaction_id IS NULL OR :NEW.last_transaction_id <> :OLD.last_transaction_id) THEN
    SELECT COUNT(*)
      INTO v_matches
      FROM wallet_transaction
     WHERE id = :NEW.last_transaction_id
       AND wallet_id = :NEW.id
       AND balance_after = :NEW.balance;
  END IF;

  INSERT INTO wallet_audit.balance_audit (wallet_id, transaction_id, previous_balance, new_balance, change_source)
  VALUES (
    :NEW.id,
    CASE WHEN v_matches = 1 THEN :NEW.last_transaction_id END,
    :OLD.balance,
    :NEW.balance,
    CASE WHEN v_matches = 1 THEN 'PACKAGE' ELSE 'DIRECT' END
  );
END;
/
