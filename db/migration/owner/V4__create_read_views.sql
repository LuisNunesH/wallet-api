CREATE OR REPLACE VIEW v_wallet AS
SELECT id,
       holder_name,
       holder_document,
       balance,
       created_at,
       updated_at
  FROM wallet
  WITH READ ONLY;

CREATE OR REPLACE VIEW v_wallet_transaction AS
SELECT id,
       wallet_id,
       idempotency_key,
       operation_type,
       amount,
       balance_after,
       description,
       created_at
  FROM wallet_transaction
  WITH READ ONLY;
