package com.walletapi.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.walletapi.support.AbstractApiIT;
import com.walletapi.support.LedgerSnapshot;
import com.walletapi.wallet.web.TransactionResponse;
import com.walletapi.wallet.web.WalletResponse;

class WalletApiIT extends AbstractApiIT {

    @Test
    @DisplayName("fluxo completo: cria carteira, credita, debita e consulta saldo e extrato")
    void fullWalletLifecycle() throws Exception {
        WalletResponse wallet = createWallet("  Maria Silva  ");
        assertThat(wallet.holderName()).isEqualTo("Maria Silva");
        assertThat(wallet.balance()).isEqualByComparingTo("0.00");

        HttpResult credit = transaction(wallet.id(), "dep-1", "CREDIT", "150.75", "Depósito inicial");
        HttpResult debit = transaction(wallet.id(), "deb-1", "DEBIT", "50.25");

        assertThat(credit.status()).isEqualTo(201);
        assertThat(credit.as(TransactionResponse.class).balanceAfter()).isEqualByComparingTo("150.75");
        assertThat(credit.as(TransactionResponse.class).description()).isEqualTo("Depósito inicial");
        assertThat(debit.status()).isEqualTo(201);
        assertThat(debit.as(TransactionResponse.class).balanceAfter()).isEqualByComparingTo("100.50");

        assertThat(get("/wallets/" + wallet.id()).as(WalletResponse.class).balance()).isEqualByComparingTo("100.50");

        Map<String, Object> statement = get("/wallets/" + wallet.id() + "/transactions").asMap();
        assertThat(statement.get("totalElements")).isEqualTo(2);
        assertThat((List<?>) statement.get("content")).hasSize(2);

        LedgerSnapshot snapshot = LedgerSnapshot.of(wallet.id());
        assertThat(snapshot.balance()).isEqualByComparingTo("100.50");
        assertThat(snapshot.audit()).hasSize(2)
                .allSatisfy(row -> {
                    assertThat(row.source()).isEqualTo("PACKAGE");
                    assertThat(row.dbUser()).isEqualTo("WALLET_APP");
                });
    }

    @Test
    @DisplayName("retry com a mesma chave e payload devolve o resultado original e não altera o saldo")
    void retryReturnsOriginalResult() throws Exception {
        long walletId = createWallet("Retry").id();

        HttpResult first = transaction(walletId, "pix-123", "CREDIT", "80.00");
        transaction(walletId, "outro", "CREDIT", "20.00");
        HttpResult retry = transaction(walletId, "pix-123", "CREDIT", "80.00");

        assertThat(first.headers().getFirst("Idempotent-Replayed")).isEqualTo("false");
        assertThat(retry.status()).isEqualTo(201);
        assertThat(retry.headers().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(retry.as(TransactionResponse.class)).isEqualTo(first.as(TransactionResponse.class));
        assertThat(retry.as(TransactionResponse.class).balanceAfter()).isEqualByComparingTo("80.00");
        assertThat(LedgerSnapshot.of(walletId).balance()).isEqualByComparingTo("100.00");
        assertThat(LedgerSnapshot.of(walletId).entries()).hasSize(2);
    }

    @Test
    @DisplayName("mesma chave com payload diferente retorna 409 e não altera nada")
    void sameKeyWithDifferentPayloadIsConflict() throws Exception {
        long walletId = createWallet("Conflito").id();
        transaction(walletId, "op-1", "CREDIT", "10.00");

        HttpResult otherAmount = transaction(walletId, "op-1", "CREDIT", "11.00");
        HttpResult otherType = transaction(walletId, "op-1", "DEBIT", "10.00");

        assertThat(otherAmount.status()).isEqualTo(409);
        assertThat(otherAmount.asMap().get("code")).isEqualTo("IDEMPOTENCY_KEY_CONFLICT");
        assertThat(otherType.status()).isEqualTo(409);
        assertThat(LedgerSnapshot.of(walletId).entries()).hasSize(1);
    }

    @Test
    @DisplayName("a mesma chave pode ser usada em carteiras diferentes")
    void idempotencyKeyIsScopedPerWallet() {
        long first = createWallet("A").id();
        long second = createWallet("B").id();

        assertThat(transaction(first, "mesma-chave", "CREDIT", "1.00").status()).isEqualTo(201);
        assertThat(transaction(second, "mesma-chave", "CREDIT", "1.00").headers().getFirst("Idempotent-Replayed"))
                .isEqualTo("false");
    }

    @Test
    @DisplayName("débito maior que o saldo retorna 422 e não gera lançamento nem auditoria")
    void debitWithoutFundsIsRejected() throws Exception {
        long walletId = createWallet("Sem saldo").id();
        transaction(walletId, "dep", "CREDIT", "30.00");

        HttpResult debit = transaction(walletId, "deb", "DEBIT", "30.01");

        assertThat(debit.status()).isEqualTo(422);
        assertThat(debit.asMap().get("code")).isEqualTo("INSUFFICIENT_FUNDS");
        LedgerSnapshot snapshot = LedgerSnapshot.of(walletId);
        assertThat(snapshot.balance()).isEqualByComparingTo("30.00");
        assertThat(snapshot.entries()).hasSize(1);
        assertThat(snapshot.audit()).hasSize(1);
    }

    @Test
    @DisplayName("débito de todo o saldo deixa a carteira exatamente em 0.00")
    void debitOfWholeBalanceIsAllowed() throws Exception {
        long walletId = createWallet("Zerar").id();
        transaction(walletId, "dep", "CREDIT", "30.00");

        assertThat(transaction(walletId, "deb", "DEBIT", "30.00").status()).isEqualTo(201);
        assertThat(LedgerSnapshot.of(walletId).balance()).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("operação em carteira inexistente retorna 404 vindo do PKG_WALLET")
    void operationOnUnknownWalletIsNotFound() {
        HttpResult result = transaction(999_999_999L, "x", "CREDIT", "1.00");

        assertThat(result.status()).isEqualTo(404);
        assertThat(result.asMap().get("code")).isEqualTo("WALLET_NOT_FOUND");
    }

    @Test
    @DisplayName("extrato pagina do mais recente para o mais antigo e filtra por período")
    void statementIsPaginatedAndFilteredByPeriod() {
        long walletId = createWallet("Extrato").id();
        for (int i = 1; i <= 5; i++) {
            transaction(walletId, "op-" + i, "CREDIT", i + ".00");
        }

        Map<String, Object> firstPage = get("/wallets/" + walletId + "/transactions?page=0&size=2").asMap();
        Map<String, Object> lastPage = get("/wallets/" + walletId + "/transactions?page=2&size=2").asMap();

        assertThat(firstPage.get("totalElements")).isEqualTo(5);
        assertThat(firstPage.get("totalPages")).isEqualTo(3);
        assertThat(amounts(firstPage)).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("5.00"), new BigDecimal("4.00"));
        assertThat(amounts(lastPage)).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("1.00"));

        String future = OffsetDateTime.now(ZoneOffset.UTC).plusDays(1).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        String past = OffsetDateTime.now(ZoneOffset.UTC).minusDays(1).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        assertThat(get("/wallets/" + walletId + "/transactions?from=" + future).asMap().get("totalElements")).isEqualTo(0);
        assertThat(get("/wallets/" + walletId + "/transactions?from=" + past + "&to=" + future).asMap()
                .get("totalElements")).isEqualTo(5);
    }

    @Test
    @DisplayName("requisição sem API key é bloqueada antes de chegar ao banco")
    void requestWithoutApiKeyIsUnauthorized() {
        int status = client().mutate()
                .defaultHeaders(headers -> headers.remove("X-API-Key"))
                .build()
                .get().uri("/wallets/1")
                .retrieve().toBodilessEntity().getStatusCode().value();

        assertThat(status).isEqualTo(401);
    }

    private static List<BigDecimal> amounts(Map<String, Object> page) {
        return ((List<?>) page.get("content")).stream()
                .map(item -> new BigDecimal(String.valueOf(((Map<?, ?>) item).get("amount"))))
                .toList();
    }
}
