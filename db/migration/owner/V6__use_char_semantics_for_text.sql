ALTER TABLE wallet MODIFY holder_name VARCHAR2(150 CHAR);

ALTER TABLE wallet_transaction MODIFY description VARCHAR2(140 CHAR);

ALTER VIEW v_wallet COMPILE;

ALTER VIEW v_wallet_transaction COMPILE;
