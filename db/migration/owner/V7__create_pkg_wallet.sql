CREATE OR REPLACE PACKAGE pkg_wallet AUTHID DEFINER AS

  c_credit CONSTANT VARCHAR2(6) := 'CREDIT';
  c_debit  CONSTANT VARCHAR2(6) := 'DEBIT';

  c_err_invalid_holder_name     CONSTANT PLS_INTEGER := -20001;
  c_err_invalid_holder_document CONSTANT PLS_INTEGER := -20002;
  c_err_invalid_operation_type  CONSTANT PLS_INTEGER := -20003;
  c_err_invalid_amount          CONSTANT PLS_INTEGER := -20004;
  c_err_invalid_idempotency_key CONSTANT PLS_INTEGER := -20005;
  c_err_invalid_description     CONSTANT PLS_INTEGER := -20006;
  c_err_wallet_not_found        CONSTANT PLS_INTEGER := -20100;
  c_err_idempotency_conflict    CONSTANT PLS_INTEGER := -20200;
  c_err_wallet_busy             CONSTANT PLS_INTEGER := -20201;
  c_err_insufficient_funds      CONSTANT PLS_INTEGER := -20300;
  c_err_balance_limit_exceeded  CONSTANT PLS_INTEGER := -20301;

  PROCEDURE create_wallet(
    p_holder_name     IN  VARCHAR2,
    p_holder_document IN  VARCHAR2,
    o_wallet_id       OUT NUMBER,
    o_balance         OUT NUMBER,
    o_created_at      OUT TIMESTAMP WITH TIME ZONE
  );

  PROCEDURE register_transaction(
    p_wallet_id       IN  NUMBER,
    p_idempotency_key IN  VARCHAR2,
    p_operation_type  IN  VARCHAR2,
    p_amount          IN  NUMBER,
    p_description     IN  VARCHAR2,
    o_transaction_id  OUT NUMBER,
    o_balance_after   OUT NUMBER,
    o_created_at      OUT TIMESTAMP WITH TIME ZONE,
    o_replayed        OUT NUMBER
  );

END pkg_wallet;
/

CREATE OR REPLACE PACKAGE BODY pkg_wallet AS

  c_max_amount CONSTANT NUMBER := 9999999999999.99;

  e_lock_timeout EXCEPTION;
  PRAGMA EXCEPTION_INIT(e_lock_timeout, -30006);

  e_resource_busy EXCEPTION;
  PRAGMA EXCEPTION_INIT(e_resource_busy, -54);

  PROCEDURE fail(p_code IN PLS_INTEGER, p_message IN VARCHAR2) IS
  BEGIN
    RAISE_APPLICATION_ERROR(p_code, p_message);
  END fail;

  PROCEDURE validate_holder(p_holder_name IN VARCHAR2, p_holder_document IN VARCHAR2) IS
  BEGIN
    IF TRIM(p_holder_name) IS NULL OR LENGTH(TRIM(p_holder_name)) > 150 THEN
      fail(c_err_invalid_holder_name, 'Nome do titular é obrigatório e deve ter até 150 caracteres');
    END IF;

    IF p_holder_document IS NOT NULL
       AND NOT REGEXP_LIKE(p_holder_document, '^([0-9]{11}|[0-9]{14})$') THEN
      fail(c_err_invalid_holder_document, 'Documento do titular deve conter 11 (CPF) ou 14 (CNPJ) dígitos');
    END IF;
  END validate_holder;

  PROCEDURE validate_operation(
    p_idempotency_key IN VARCHAR2,
    p_operation_type  IN VARCHAR2,
    p_amount          IN NUMBER,
    p_description     IN VARCHAR2
  ) IS
  BEGIN
    IF p_idempotency_key IS NULL
       OR NOT REGEXP_LIKE(p_idempotency_key, '^[A-Za-z0-9_.:-]{1,64}$') THEN
      fail(c_err_invalid_idempotency_key,
           'Chave de idempotência obrigatória, com até 64 caracteres alfanuméricos ou _ . : -');
    END IF;

    IF p_operation_type IS NULL OR p_operation_type NOT IN (c_credit, c_debit) THEN
      fail(c_err_invalid_operation_type, 'Tipo de operação deve ser CREDIT ou DEBIT');
    END IF;

    IF p_amount IS NULL OR p_amount <= 0 THEN
      fail(c_err_invalid_amount, 'Valor da operação deve ser maior que zero');
    END IF;

    IF p_amount <> ROUND(p_amount, 2) THEN
      fail(c_err_invalid_amount, 'Valor da operação deve ter no máximo 2 casas decimais');
    END IF;

    IF p_amount > c_max_amount THEN
      fail(c_err_invalid_amount, 'Valor da operação excede o limite permitido');
    END IF;

    IF LENGTH(p_description) > 140 THEN
      fail(c_err_invalid_description, 'Descrição deve ter até 140 caracteres');
    END IF;
  END validate_operation;

  FUNCTION lock_wallet(p_wallet_id IN NUMBER) RETURN NUMBER IS
    v_balance wallet.balance%TYPE;
  BEGIN
    SELECT balance
      INTO v_balance
      FROM wallet
     WHERE id = p_wallet_id
       FOR UPDATE WAIT 5;

    RETURN v_balance;
  EXCEPTION
    WHEN NO_DATA_FOUND THEN
      fail(c_err_wallet_not_found, 'Carteira ' || p_wallet_id || ' não encontrada');
    WHEN e_lock_timeout OR e_resource_busy THEN
      fail(c_err_wallet_busy, 'Carteira ' || p_wallet_id || ' ocupada por outra operação, tente novamente');
  END lock_wallet;

  FUNCTION find_by_idempotency_key(
    p_wallet_id       IN NUMBER,
    p_idempotency_key IN VARCHAR2,
    o_transaction     OUT wallet_transaction%ROWTYPE
  ) RETURN BOOLEAN IS
  BEGIN
    SELECT *
      INTO o_transaction
      FROM wallet_transaction
     WHERE wallet_id = p_wallet_id
       AND idempotency_key = p_idempotency_key;

    RETURN TRUE;
  EXCEPTION
    WHEN NO_DATA_FOUND THEN
      RETURN FALSE;
  END find_by_idempotency_key;

  FUNCTION same_payload(
    p_transaction    IN wallet_transaction%ROWTYPE,
    p_operation_type IN VARCHAR2,
    p_amount         IN NUMBER,
    p_description    IN VARCHAR2
  ) RETURN BOOLEAN IS
  BEGIN
    RETURN p_transaction.operation_type = p_operation_type
       AND p_transaction.amount = p_amount
       AND (p_transaction.description = p_description
            OR (p_transaction.description IS NULL AND p_description IS NULL));
  END same_payload;

  PROCEDURE create_wallet(
    p_holder_name     IN  VARCHAR2,
    p_holder_document IN  VARCHAR2,
    o_wallet_id       OUT NUMBER,
    o_balance         OUT NUMBER,
    o_created_at      OUT TIMESTAMP WITH TIME ZONE
  ) IS
  BEGIN
    validate_holder(p_holder_name, p_holder_document);

    INSERT INTO wallet (holder_name, holder_document)
    VALUES (TRIM(p_holder_name), p_holder_document)
    RETURNING id, balance, created_at INTO o_wallet_id, o_balance, o_created_at;

    COMMIT;
  EXCEPTION
    WHEN OTHERS THEN
      ROLLBACK;
      RAISE;
  END create_wallet;

  PROCEDURE register_transaction(
    p_wallet_id       IN  NUMBER,
    p_idempotency_key IN  VARCHAR2,
    p_operation_type  IN  VARCHAR2,
    p_amount          IN  NUMBER,
    p_description     IN  VARCHAR2,
    o_transaction_id  OUT NUMBER,
    o_balance_after   OUT NUMBER,
    o_created_at      OUT TIMESTAMP WITH TIME ZONE,
    o_replayed        OUT NUMBER
  ) IS
    v_balance     wallet.balance%TYPE;
    v_new_balance NUMBER;
    v_existing    wallet_transaction%ROWTYPE;
  BEGIN
    validate_operation(p_idempotency_key, p_operation_type, p_amount, p_description);

    v_balance := lock_wallet(p_wallet_id);

    IF find_by_idempotency_key(p_wallet_id, p_idempotency_key, v_existing) THEN
      IF NOT same_payload(v_existing, p_operation_type, p_amount, p_description) THEN
        fail(c_err_idempotency_conflict,
             'Chave de idempotência ' || p_idempotency_key || ' já usada com outro payload nesta carteira');
      END IF;

      o_transaction_id := v_existing.id;
      o_balance_after  := v_existing.balance_after;
      o_created_at     := v_existing.created_at;
      o_replayed       := 1;
      COMMIT;
      RETURN;
    END IF;

    IF p_operation_type = c_debit THEN
      IF v_balance < p_amount THEN
        fail(c_err_insufficient_funds, 'Saldo insuficiente para o débito');
      END IF;
      v_new_balance := v_balance - p_amount;
    ELSE
      v_new_balance := v_balance + p_amount;
      IF v_new_balance > c_max_amount THEN
        fail(c_err_balance_limit_exceeded, 'Crédito faria o saldo exceder o limite permitido');
      END IF;
    END IF;

    INSERT INTO wallet_transaction (wallet_id, idempotency_key, operation_type, amount, balance_after, description)
    VALUES (p_wallet_id, p_idempotency_key, p_operation_type, p_amount, v_new_balance, p_description)
    RETURNING id, created_at INTO o_transaction_id, o_created_at;

    UPDATE wallet
       SET balance             = v_new_balance,
           last_transaction_id = o_transaction_id,
           updated_at          = SYSTIMESTAMP
     WHERE id = p_wallet_id;

    o_balance_after := v_new_balance;
    o_replayed      := 0;
    COMMIT;
  EXCEPTION
    WHEN DUP_VAL_ON_INDEX THEN
      ROLLBACK;
      fail(c_err_idempotency_conflict,
           'Chave de idempotência ' || p_idempotency_key || ' já usada nesta carteira');
    WHEN OTHERS THEN
      ROLLBACK;
      RAISE;
  END register_transaction;

END pkg_wallet;
/
