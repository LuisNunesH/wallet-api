package com.walletapi.wallet.persistence;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.CallableStatement;
import java.sql.Types;
import java.time.OffsetDateTime;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.walletapi.error.OracleErrorTranslator;
import com.walletapi.wallet.domain.TransactionCommand;
import com.walletapi.wallet.domain.TransactionResult;
import com.walletapi.wallet.domain.WalletTransaction;

@Component
public class WalletProcedureGateway {

    private static final String CREATE_WALLET = "{call pkg_wallet.create_wallet(?, ?, ?, ?, ?)}";
    private static final String REGISTER_TRANSACTION =
            "{call pkg_wallet.register_transaction(?, ?, ?, ?, ?, ?, ?, ?, ?)}";

    private final JdbcTemplate jdbc;
    private final OracleErrorTranslator errorTranslator;

    public WalletProcedureGateway(JdbcTemplate jdbc, OracleErrorTranslator errorTranslator) {
        this.jdbc = jdbc;
        this.errorTranslator = errorTranslator;
    }

    public long createWallet(String holderName, String holderDocument) {
        try {
            Long walletId = jdbc.execute(CREATE_WALLET, (CallableStatement cs) -> {
                cs.setString(1, holderName);
                cs.setString(2, holderDocument);
                cs.registerOutParameter(3, Types.NUMERIC);
                cs.registerOutParameter(4, Types.NUMERIC);
                cs.registerOutParameter(5, Types.TIMESTAMP_WITH_TIMEZONE);
                cs.execute();
                return cs.getLong(3);
            });
            return walletId;
        } catch (DataAccessException exception) {
            throw errorTranslator.translate(exception);
        }
    }

    public TransactionResult registerTransaction(TransactionCommand command) {
        try {
            return jdbc.execute(REGISTER_TRANSACTION, (CallableStatement cs) -> {
                cs.setLong(1, command.walletId());
                cs.setString(2, command.idempotencyKey());
                cs.setString(3, command.type().name());
                cs.setBigDecimal(4, command.amount());
                cs.setString(5, command.description());
                cs.registerOutParameter(6, Types.NUMERIC);
                cs.registerOutParameter(7, Types.NUMERIC);
                cs.registerOutParameter(8, Types.TIMESTAMP_WITH_TIMEZONE);
                cs.registerOutParameter(9, Types.NUMERIC);
                cs.execute();

                WalletTransaction transaction = new WalletTransaction(
                        cs.getLong(6),
                        command.walletId(),
                        command.idempotencyKey(),
                        command.type(),
                        command.amount().setScale(2, RoundingMode.UNNECESSARY),
                        cs.getBigDecimal(7).setScale(2, RoundingMode.UNNECESSARY),
                        command.description(),
                        cs.getObject(8, OffsetDateTime.class));
                return new TransactionResult(transaction, cs.getInt(9) == 1);
            });
        } catch (DataAccessException exception) {
            throw errorTranslator.translate(exception);
        }
    }
}
