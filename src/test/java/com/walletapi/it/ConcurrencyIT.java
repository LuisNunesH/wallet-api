package com.walletapi.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.walletapi.support.AbstractApiIT;
import com.walletapi.support.LedgerSnapshot;
import com.walletapi.wallet.web.TransactionResponse;

class ConcurrencyIT extends AbstractApiIT {

    @Test
    @DisplayName("40 débitos simultâneos de 5.00 sobre 100.00: exatamente 20 aplicados, saldo final 0.00, nunca negativo")
    void concurrentDebitsNeverOverdraw() throws Exception {
        long walletId = createWallet("Corrida de débitos").id();
        transaction(walletId, "seed", "CREDIT", "100.00");

        List<HttpResult> results = runConcurrently(40, i -> transaction(walletId, "debit-" + i, "DEBIT", "5.00"));

        assertThat(countByStatus(results)).containsOnlyKeys(201, 422)
                .containsEntry(201, 20L)
                .containsEntry(422, 20L);

        LedgerSnapshot snapshot = LedgerSnapshot.of(walletId);
        assertThat(snapshot.balance()).isEqualByComparingTo("0.00");
        assertThat(snapshot.entries()).hasSize(21);
        assertThat(snapshot.entries()).allSatisfy(entry -> assertThat(entry.balanceAfter()).isNotNegative());
        assertThat(snapshot.entries().stream().map(entry -> entry.balanceAfter().stripTrailingZeros()).distinct())
                .as("cada débito viu um saldo diferente, ou seja, foram serializados")
                .hasSize(21);
        assertConsistent(snapshot);
    }

    @Test
    @DisplayName("30 requisições simultâneas com a mesma Idempotency-Key aplicam o crédito uma única vez")
    void concurrentRetriesAreAppliedOnce() throws Exception {
        long walletId = createWallet("Retry concorrente").id();

        List<HttpResult> results = runConcurrently(30, i -> transaction(walletId, "same-key", "CREDIT", "10.00"));

        assertThat(results).allSatisfy(result -> assertThat(result.status()).isEqualTo(201));
        assertThat(results.stream().map(result -> result.as(TransactionResponse.class).id()).distinct()).hasSize(1);
        assertThat(results.stream().filter(result -> "true".equals(result.headers().getFirst("Idempotent-Replayed"))))
                .hasSize(29);

        LedgerSnapshot snapshot = LedgerSnapshot.of(walletId);
        assertThat(snapshot.balance()).isEqualByComparingTo("10.00");
        assertThat(snapshot.entries()).hasSize(1);
        assertConsistent(snapshot);
    }

    @Test
    @DisplayName("créditos e débitos misturados, cada um enviado em duplicidade: saldo = soma do que foi aplicado")
    void mixedOperationsWithDuplicatesStayConsistent() throws Exception {
        long walletId = createWallet("Carga mista").id();
        transaction(walletId, "seed", "CREDIT", "50.00");

        Random random = new Random(42);
        List<String[]> operations = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            String type = random.nextBoolean() ? "CREDIT" : "DEBIT";
            String amount = BigDecimal.valueOf(100 + random.nextInt(4900), 2).toPlainString();
            operations.add(new String[] {"mixed-" + i, type, amount});
        }

        List<HttpResult> results = runConcurrently(operations.size() * 2, i -> {
            String[] operation = operations.get(i / 2);
            return transaction(walletId, operation[0], operation[1], operation[2]);
        });

        assertThat(countByStatus(results).keySet()).isSubsetOf(201, 422);
        assertThat(countByStatus(results)).as("o cenário precisa forçar débitos recusados").containsKey(422);

        Map<Long, TransactionResponse> applied = new HashMap<>();
        results.stream()
                .filter(result -> result.status() == 201)
                .map(result -> result.as(TransactionResponse.class))
                .forEach(response -> applied.merge(response.id(), response, (first, second) -> {
                    assertThat(second).as("respostas para a mesma operação devem ser idênticas").isEqualTo(first);
                    return first;
                }));

        BigDecimal expected = applied.values().stream()
                .map(tx -> tx.type().name().equals("CREDIT") ? tx.amount() : tx.amount().negate())
                .reduce(new BigDecimal("50.00"), BigDecimal::add);

        LedgerSnapshot snapshot = LedgerSnapshot.of(walletId);
        assertThat(snapshot.balance()).isEqualByComparingTo(expected);
        assertThat(snapshot.entries()).hasSize(applied.size() + 1);
        assertThat(snapshot.entries()).allSatisfy(entry -> assertThat(entry.balanceAfter()).isNotNegative());
        assertConsistent(snapshot);
    }

    private static void assertConsistent(LedgerSnapshot snapshot) {
        assertThat(snapshot.ledgerSum()).as("saldo = soma do extrato").isEqualByComparingTo(snapshot.balance());

        assertThat(snapshot.audit()).as("uma linha de auditoria por lançamento").hasSize(snapshot.entries().size());
        assertThat(snapshot.audit()).allSatisfy(row -> {
            assertThat(row.source()).isEqualTo("PACKAGE");
            assertThat(row.newBalance()).isNotNegative();
        });
        assertThat(snapshot.audit().stream().map(LedgerSnapshot.AuditRow::transactionId).toList())
                .as("auditoria aponta para os lançamentos, na mesma ordem")
                .containsExactlyElementsOf(snapshot.entries().stream().map(LedgerSnapshot.Entry::id).toList());

        BigDecimal previous = BigDecimal.ZERO;
        for (LedgerSnapshot.AuditRow row : snapshot.audit()) {
            assertThat(row.previousBalance()).as("trilha encadeada sem buracos").isEqualByComparingTo(previous);
            previous = row.newBalance();
        }
        assertThat(previous).isEqualByComparingTo(snapshot.balance());
    }

    private static Map<Integer, Long> countByStatus(List<HttpResult> results) {
        return results.stream().collect(Collectors.groupingBy(HttpResult::status, Collectors.counting()));
    }

    private interface Request {
        HttpResult send(int index);
    }

    private static List<HttpResult> runConcurrently(int count, Request request) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(count);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<HttpResult>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                int index = i;
                Callable<HttpResult> task = () -> {
                    start.await();
                    return request.send(index);
                };
                futures.add(executor.submit(task));
            }
            start.countDown();
            List<HttpResult> results = new ArrayList<>();
            for (Future<HttpResult> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }
}
