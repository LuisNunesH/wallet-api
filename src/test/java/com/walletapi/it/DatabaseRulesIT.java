package com.walletapi.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.walletapi.support.LedgerSnapshot;
import com.walletapi.support.OracleTestDatabase;

class DatabaseRulesIT {

    private static final int TABLE_NOT_VISIBLE = 942;
    private static final int INSUFFICIENT_PRIVILEGES = 1031;
    private static final int MISSING_PRIVILEGE = 41900;
    private static final int CHECK_CONSTRAINT = 2290;
    private static final Pattern ORACLE_CODE = Pattern.compile("ORA-(\\d{5})");

    private static long walletId;

    @BeforeAll
    static void createWalletWithHistory() throws SQLException {
        walletId = createWallet("Regras do banco");
        register(walletId, "dep-1", "CREDIT", "100.00");
        register(walletId, "deb-1", "DEBIT", "40.00");
    }

    @Nested
    @DisplayName("privilégios do WALLET_APP")
    class AppPrivileges {

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '|', value = {
                "INSERT INTO wallet_owner.wallet (holder_name) VALUES ('x')",
                "UPDATE wallet_owner.wallet SET balance = 1000000",
                "DELETE FROM wallet_owner.wallet_transaction",
                "SELECT COUNT(*) FROM wallet_owner.wallet",
                "SELECT COUNT(*) FROM wallet_audit.balance_audit"
        })
        @DisplayName("não enxerga tabelas nem a trilha de auditoria")
        void cannotTouchTables(String sql) {
            assertThat(oracleErrors(OracleTestDatabase::connectAsApp, sql)).contains(TABLE_NOT_VISIBLE);
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '|', value = {
                "UPDATE wallet_owner.v_wallet SET balance = 0",
                "DELETE FROM wallet_owner.v_wallet_transaction",
                "SELECT id FROM wallet_owner.v_wallet FOR UPDATE"
        })
        @DisplayName("lê as views, mas não escreve nem trava linhas por elas")
        void viewsAreReadOnlyAndCannotLock(String sql) {
            assertThat(oracleErrors(OracleTestDatabase::connectAsApp, sql))
                    .containsAnyOf(INSUFFICIENT_PRIVILEGES, MISSING_PRIVILEGE);
        }

        @Test
        @DisplayName("consegue ler pelas views")
        void canReadViews() throws SQLException {
            try (Connection app = OracleTestDatabase.connectAsApp(); Statement statement = app.createStatement()) {
                assertThat(statement.executeQuery("SELECT COUNT(*) FROM wallet_owner.v_wallet_transaction").next()).isTrue();
            }
        }
    }

    @Nested
    @DisplayName("imutabilidade do extrato e da auditoria")
    class Immutability {

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '|', value = {
                "UPDATE wallet_transaction SET amount = 1",
                "DELETE FROM wallet_transaction WHERE id = -1",
                "TRUNCATE TABLE wallet_transaction",
                "DROP TABLE wallet_transaction"
        })
        @DisplayName("nem o WALLET_OWNER altera ou apaga lançamentos")
        void ledgerIsImmutableForTheOwner(String sql) {
            assertThat(oracleErrors(OracleTestDatabase::connectAsOwner, sql)).contains(20900);
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '|', value = {
                "UPDATE balance_audit SET new_balance = 0",
                "DELETE FROM balance_audit",
                "TRUNCATE TABLE balance_audit",
                "DROP TABLE balance_audit"
        })
        @DisplayName("nem o WALLET_AUDIT altera ou apaga a trilha")
        void auditIsImmutableForItsOwner(String sql) {
            assertThat(oracleErrors(OracleTestDatabase::connectAsAudit, sql)).contains(20901);
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '|', value = {
                "SELECT COUNT(*) FROM wallet_audit.balance_audit",
                "DELETE FROM wallet_audit.balance_audit"
        })
        @DisplayName("o WALLET_OWNER só insere na trilha: não lê nem apaga")
        void ownerCanOnlyInsertIntoAudit(String sql) {
            assertThat(oracleErrors(OracleTestDatabase::connectAsOwner, sql))
                    .containsAnyOf(INSUFFICIENT_PRIVILEGES, MISSING_PRIVILEGE);
        }
    }

    @Nested
    @DisplayName("trilha de auditoria")
    class AuditTrail {

        @Test
        @DisplayName("cada operação do package gera auditoria com saldo anterior, novo, transação e usuário")
        void packageOperationsAreAudited() throws SQLException {
            LedgerSnapshot snapshot = LedgerSnapshot.of(walletId);

            assertThat(snapshot.audit()).hasSizeGreaterThanOrEqualTo(2);
            LedgerSnapshot.AuditRow credit = snapshot.audit().get(0);
            LedgerSnapshot.AuditRow debit = snapshot.audit().get(1);
            assertThat(credit.previousBalance()).isEqualByComparingTo("0.00");
            assertThat(credit.newBalance()).isEqualByComparingTo("100.00");
            assertThat(credit.transactionId()).isEqualTo(snapshot.entries().get(0).id());
            assertThat(debit.previousBalance()).isEqualByComparingTo("100.00");
            assertThat(debit.newBalance()).isEqualByComparingTo("60.00");
            assertThat(debit.source()).isEqualTo("PACKAGE");
            assertThat(debit.dbUser()).isEqualTo("WALLET_APP");
        }

        @Test
        @DisplayName("alteração de saldo por SQL direto, fora do package, é registrada como DIRECT")
        void directBalanceChangeIsFlagged() throws SQLException {
            long target = createWallet("Alteração direta");
            register(target, "dep", "CREDIT", "10.00");

            try (Connection owner = OracleTestDatabase.connectAsOwner(); Statement statement = owner.createStatement()) {
                statement.executeUpdate("UPDATE wallet SET balance = 999 WHERE id = " + target);
            }

            LedgerSnapshot.AuditRow last = LedgerSnapshot.of(target).audit().get(1);
            assertThat(last.source()).isEqualTo("DIRECT");
            assertThat(last.transactionId()).isNull();
            assertThat(last.previousBalance()).isEqualByComparingTo("10.00");
            assertThat(last.newBalance()).isEqualByComparingTo("999.00");
            assertThat(last.dbUser()).isEqualTo("WALLET_OWNER");
        }

        @Test
        @DisplayName("forjar o last_transaction_id com um lançamento antigo não engana a auditoria")
        void forgedTransactionReferenceIsFlagged() throws SQLException {
            long target = createWallet("Forjado");
            long firstTx = register(target, "dep-1", "CREDIT", "10.00");
            register(target, "dep-2", "CREDIT", "5.00");

            try (Connection owner = OracleTestDatabase.connectAsOwner(); Statement statement = owner.createStatement()) {
                statement.executeUpdate(
                        "UPDATE wallet SET balance = 500, last_transaction_id = " + firstTx + " WHERE id = " + target);
            }

            assertThat(LedgerSnapshot.of(target).audit().get(2).source()).isEqualTo("DIRECT");
        }

        @Test
        @DisplayName("a CHECK constraint barra saldo negativo mesmo fora do package")
        void negativeBalanceIsBlockedByConstraint() {
            assertThat(oracleErrors(OracleTestDatabase::connectAsOwner,
                    "UPDATE wallet SET balance = -0.01 WHERE id = " + walletId)).contains(CHECK_CONSTRAINT);
        }
    }

    @Nested
    @DisplayName("validações do PKG_WALLET chamadas sem passar pela API")
    class PackageValidation {

        @ParameterizedTest(name = "{1} {2} key={0} -> ORA-{3}")
        @CsvSource(nullValues = "NULL", value = {
                "k1,              CREDIT, 0,     20004",
                "k2,              CREDIT, -5,    20004",
                "k3,              CREDIT, 1.999, 20004",
                "k4,              CREDIT, 10000000000000, 20004",
                "k5,              PIX,    10,    20003",
                "NULL,            CREDIT, 10,    20005",
                "chave com espaço, CREDIT, 10,   20005",
                "k6,              DEBIT,  1000,  20300"
        })
        @DisplayName("rejeita operação inválida com o código documentado")
        void rejectsInvalidOperations(String key, String type, String amount, int expectedCode) {
            assertThatThrownBy(() -> register(walletId, key, type, amount))
                    .isInstanceOfSatisfying(SQLException.class,
                            exception -> assertThat(exception.getErrorCode()).isEqualTo(expectedCode));
        }

        @Test
        @DisplayName("rejeita descrição com mais de 140 caracteres")
        void rejectsLongDescription() {
            assertThatThrownBy(() -> register(walletId, "desc", "CREDIT", "1.00", "x".repeat(141)))
                    .isInstanceOfSatisfying(SQLException.class,
                            exception -> assertThat(exception.getErrorCode()).isEqualTo(20006));
        }

        @Test
        @DisplayName("aceita descrição de 140 caracteres acentuados")
        void acceptsAccentedDescriptionAtTheLimit() throws SQLException {
            long target = createWallet("Acentos");
            register(target, "desc", "CREDIT", "1.00", "ç".repeat(140));
        }

        @Test
        @DisplayName("rejeita carteira inexistente com ORA-20100")
        void rejectsUnknownWallet() {
            assertThatThrownBy(() -> register(-1, "k", "CREDIT", "1.00"))
                    .isInstanceOfSatisfying(SQLException.class,
                            exception -> assertThat(exception.getErrorCode()).isEqualTo(20100));
        }

        @ParameterizedTest(name = "titular=''{0}'' documento={1} -> ORA-{2}")
        @CsvSource(nullValues = "NULL", value = {
                "'   ', NULL,        20001",
                "Ana,   123,         20002",
                "Ana,   1234567890a, 20002"
        })
        @DisplayName("rejeita titular inválido na criação de carteira")
        void rejectsInvalidHolder(String name, String document, int expectedCode) {
            assertThatThrownBy(() -> createWallet(name, document))
                    .isInstanceOfSatisfying(SQLException.class,
                            exception -> assertThat(exception.getErrorCode()).isEqualTo(expectedCode));
        }

        @Test
        @DisplayName("débito rejeitado não deixa lançamento nem auditoria")
        void rejectedDebitLeavesNoTrace() throws SQLException {
            long target = createWallet("Rejeitado");
            register(target, "dep", "CREDIT", "5.00");

            assertThatThrownBy(() -> register(target, "deb", "DEBIT", "5.01")).isInstanceOf(SQLException.class);

            LedgerSnapshot snapshot = LedgerSnapshot.of(target);
            assertThat(snapshot.entries()).hasSize(1);
            assertThat(snapshot.audit()).hasSize(1);
            assertThat(snapshot.balance()).isEqualByComparingTo("5.00");
        }
    }

    @Test
    @DisplayName("carteira travada por outra sessão: espera até 5s e devolve ORA-20201, sem esperar para sempre")
    void lockedWalletFailsFastWithWalletBusy() throws SQLException {
        long target = createWallet("Travada");
        try (Connection owner = OracleTestDatabase.connectAsOwner()) {
            owner.setAutoCommit(false);
            try (Statement lock = owner.createStatement()) {
                lock.executeQuery("SELECT id FROM wallet WHERE id = " + target + " FOR UPDATE");

                Instant start = Instant.now();
                assertThatThrownBy(() -> register(target, "k", "CREDIT", "1.00"))
                        .isInstanceOfSatisfying(SQLException.class,
                                exception -> assertThat(exception.getErrorCode()).isEqualTo(20201));
                assertThat(Duration.between(start, Instant.now())).isBetween(Duration.ofSeconds(4), Duration.ofSeconds(15));
            } finally {
                owner.rollback();
            }
        }
        assertThat(register(target, "k", "CREDIT", "1.00")).isPositive();
    }

    private interface ConnectionFactory {
        Connection open() throws SQLException;
    }

    private static List<Integer> oracleErrors(ConnectionFactory factory, String sql) {
        try (Connection connection = factory.open(); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.execute(sql);
            connection.rollback();
            throw new AssertionError("era esperado que o banco rejeitasse: " + sql);
        } catch (SQLException exception) {
            List<Integer> codes = new ArrayList<>();
            codes.add(exception.getErrorCode());
            Matcher matcher = ORACLE_CODE.matcher(String.valueOf(exception.getMessage()));
            while (matcher.find()) {
                codes.add(Integer.parseInt(matcher.group(1)));
            }
            return codes;
        }
    }

    private static long createWallet(String holderName) throws SQLException {
        return createWallet(holderName, null);
    }

    private static long createWallet(String holderName, String holderDocument) throws SQLException {
        try (Connection app = OracleTestDatabase.connectAsApp();
                CallableStatement call = app.prepareCall("{call wallet_owner.pkg_wallet.create_wallet(?, ?, ?, ?, ?)}")) {
            call.setString(1, holderName);
            call.setString(2, holderDocument);
            call.registerOutParameter(3, Types.NUMERIC);
            call.registerOutParameter(4, Types.NUMERIC);
            call.registerOutParameter(5, Types.TIMESTAMP_WITH_TIMEZONE);
            call.execute();
            return call.getLong(3);
        }
    }

    private static long register(long wallet, String key, String type, String amount) throws SQLException {
        return register(wallet, key, type, amount, null);
    }

    private static long register(long wallet, String key, String type, String amount, String description)
            throws SQLException {
        try (Connection app = OracleTestDatabase.connectAsApp();
                CallableStatement call = app.prepareCall(
                        "{call wallet_owner.pkg_wallet.register_transaction(?, ?, ?, ?, ?, ?, ?, ?, ?)}")) {
            call.setLong(1, wallet);
            call.setString(2, key);
            call.setString(3, type);
            call.setBigDecimal(4, new BigDecimal(amount));
            call.setString(5, description);
            call.registerOutParameter(6, Types.NUMERIC);
            call.registerOutParameter(7, Types.NUMERIC);
            call.registerOutParameter(8, Types.TIMESTAMP_WITH_TIMEZONE);
            call.registerOutParameter(9, Types.NUMERIC);
            call.execute();
            return call.getLong(6);
        }
    }
}
