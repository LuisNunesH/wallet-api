package com.walletapi.error;

import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

@Component
public class OracleErrorTranslator {

    static final Map<Integer, ErrorCode> BUSINESS_ERRORS = Map.ofEntries(
            Map.entry(20001, ErrorCode.INVALID_HOLDER_NAME),
            Map.entry(20002, ErrorCode.INVALID_HOLDER_DOCUMENT),
            Map.entry(20003, ErrorCode.INVALID_OPERATION_TYPE),
            Map.entry(20004, ErrorCode.INVALID_AMOUNT),
            Map.entry(20005, ErrorCode.INVALID_IDEMPOTENCY_KEY),
            Map.entry(20006, ErrorCode.INVALID_DESCRIPTION),
            Map.entry(20100, ErrorCode.WALLET_NOT_FOUND),
            Map.entry(20200, ErrorCode.IDEMPOTENCY_KEY_CONFLICT),
            Map.entry(20201, ErrorCode.WALLET_BUSY),
            Map.entry(20300, ErrorCode.INSUFFICIENT_FUNDS),
            Map.entry(20301, ErrorCode.BALANCE_LIMIT_EXCEEDED));

    private static final Pattern ORACLE_MESSAGE = Pattern.compile("^ORA-\\d{5}:\\s*(.*)$");

    public RuntimeException translate(RuntimeException exception) {
        return findSqlException(exception)
                .flatMap(this::toWalletException)
                .<RuntimeException>map(walletException -> walletException)
                .orElse(exception);
    }

    private Optional<WalletException> toWalletException(SQLException sqlException) {
        ErrorCode code = BUSINESS_ERRORS.get(sqlException.getErrorCode());
        if (code == null) {
            return Optional.empty();
        }
        return Optional.of(new WalletException(code, extractMessage(sqlException), sqlException));
    }

    static String extractMessage(SQLException sqlException) {
        String message = sqlException.getMessage();
        if (message == null || message.isBlank()) {
            return null;
        }
        String firstLine = message.lines().findFirst().orElse("").trim();
        Matcher matcher = ORACLE_MESSAGE.matcher(firstLine);
        return matcher.matches() ? matcher.group(1).trim() : firstLine;
    }

    private static Optional<SQLException> findSqlException(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SQLException sqlException) {
                return Optional.of(sqlException);
            }
            current = current.getCause();
        }
        return Optional.empty();
    }
}
