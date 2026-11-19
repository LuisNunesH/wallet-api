package com.walletapi.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.UncategorizedSQLException;

class OracleErrorTranslatorTest {

    private final OracleErrorTranslator translator = new OracleErrorTranslator();

    @ParameterizedTest(name = "ORA-{0} -> {1} ({2})")
    @CsvSource({
            "20001, INVALID_HOLDER_NAME,      400",
            "20002, INVALID_HOLDER_DOCUMENT,  400",
            "20003, INVALID_OPERATION_TYPE,   400",
            "20004, INVALID_AMOUNT,           400",
            "20005, INVALID_IDEMPOTENCY_KEY,  400",
            "20006, INVALID_DESCRIPTION,      400",
            "20100, WALLET_NOT_FOUND,         404",
            "20200, IDEMPOTENCY_KEY_CONFLICT, 409",
            "20201, WALLET_BUSY,              409",
            "20300, INSUFFICIENT_FUNDS,       422",
            "20301, BALANCE_LIMIT_EXCEEDED,   422"
    })
    @DisplayName("traduz cada código de negócio do PKG_WALLET para o erro e o status HTTP documentados")
    void translatesEveryDocumentedOracleCode(int oracleCode, ErrorCode expectedCode, int expectedStatus) {
        RuntimeException result = translator.translate(oracleFailure(oracleCode, "mensagem do banco"));

        assertThat(result).isInstanceOfSatisfying(WalletException.class, exception -> {
            assertThat(exception.code()).isEqualTo(expectedCode);
            assertThat(exception.code().status().value()).isEqualTo(expectedStatus);
        });
    }

    @Test
    @DisplayName("saldo insuficiente vira 422 com a mensagem do banco, sem prefixo ORA nem pilha PL/SQL")
    void insufficientFundsKeepsOnlyTheBusinessMessage() {
        String oracleMessage = """
                ORA-20300: Saldo insuficiente para o débito
                ORA-06512: at "WALLET_OWNER.PKG_WALLET", line 12
                ORA-06512: at "WALLET_OWNER.PKG_WALLET", line 212
                ORA-06512: at line 1

                https://docs.oracle.com/error-help/db/ora-20300/""";

        RuntimeException result = translator.translate(rawOracleFailure(20300, oracleMessage));

        assertThat(result).isInstanceOfSatisfying(WalletException.class, exception -> {
            assertThat(exception.code()).isEqualTo(ErrorCode.INSUFFICIENT_FUNDS);
            assertThat(exception.code().status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            assertThat(exception.getMessage()).isEqualTo("Saldo insuficiente para o débito");
        });
    }

    @Test
    @DisplayName("erros Oracle fora do catálogo não são traduzidos e seguem como falha inesperada")
    void unknownOracleErrorsAreNotTranslated() {
        UncategorizedSQLException uniqueViolation = rawOracleFailure(1, "ORA-00001: unique constraint violated");

        assertThat(translator.translate(uniqueViolation)).isSameAs(uniqueViolation);
    }

    @Test
    @DisplayName("códigos -20xxx não documentados também não são traduzidos")
    void undocumentedApplicationCodesAreNotTranslated() {
        UncategorizedSQLException ledgerImmutable = oracleFailure(20900, "lançamento imutável");

        assertThat(translator.translate(ledgerImmutable)).isSameAs(ledgerImmutable);
    }

    @Test
    @DisplayName("encontra a SQLException mesmo quando está aninhada em várias causas")
    void findsSqlExceptionDeepInTheCauseChain() {
        SQLException sqlException = new SQLException("ORA-20100: Carteira 7 não encontrada", "72000", 20100);
        RuntimeException wrapped = new DataAccessResourceFailureException("falha",
                new IllegalStateException("intermediária", sqlException));

        assertThat(translator.translate(wrapped)).isInstanceOfSatisfying(WalletException.class, exception -> {
            assertThat(exception.code()).isEqualTo(ErrorCode.WALLET_NOT_FOUND);
            assertThat(exception.getMessage()).isEqualTo("Carteira 7 não encontrada");
            assertThat(exception.getCause()).isSameAs(sqlException);
        });
    }

    @Test
    @DisplayName("exceções sem SQLException na causa são devolvidas intactas")
    void exceptionsWithoutSqlCauseAreReturnedAsIs() {
        IllegalStateException exception = new IllegalStateException("sem banco");

        assertThat(translator.translate(exception)).isSameAs(exception);
    }

    @Test
    @DisplayName("todo código mapeado está na faixa reservada a RAISE_APPLICATION_ERROR")
    void everyMappedCodeIsInsideTheApplicationErrorRange() {
        assertThat(OracleErrorTranslator.BUSINESS_ERRORS.keySet()).allSatisfy(code ->
                assertThat(code).isBetween(20000, 20999));
    }

    private static UncategorizedSQLException oracleFailure(int code, String message) {
        return rawOracleFailure(code, "ORA-" + code + ": " + message);
    }

    private static UncategorizedSQLException rawOracleFailure(int code, String fullMessage) {
        return new UncategorizedSQLException("call", "{call pkg_wallet.register_transaction}",
                new SQLException(fullMessage, "72000", code));
    }
}
