CREATE OR REPLACE TRIGGER trg_balance_audit_immutable
BEFORE UPDATE OR DELETE ON balance_audit
BEGIN
  RAISE_APPLICATION_ERROR(-20901, 'A trilha de auditoria é imutável e não pode ser alterada ou apagada');
END;
/

CREATE OR REPLACE TRIGGER trg_protect_audit_ddl
BEFORE TRUNCATE OR DROP ON SCHEMA
BEGIN
  IF ora_dict_obj_name = 'BALANCE_AUDIT' THEN
    RAISE_APPLICATION_ERROR(-20901, 'A trilha de auditoria não pode ser truncada nem removida');
  END IF;
END;
/
