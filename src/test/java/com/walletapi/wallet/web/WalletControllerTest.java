package com.walletapi.wallet.web;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.walletapi.error.ErrorCode;
import com.walletapi.error.OracleErrorTranslator;
import com.walletapi.error.WalletException;
import com.walletapi.security.ApiKeyProperties;
import com.walletapi.wallet.WalletService;
import com.walletapi.wallet.domain.OperationType;
import com.walletapi.wallet.domain.PageResult;
import com.walletapi.wallet.domain.TransactionCommand;
import com.walletapi.wallet.domain.TransactionResult;
import com.walletapi.wallet.domain.Wallet;
import com.walletapi.wallet.domain.WalletTransaction;

@WebMvcTest(controllers = WalletController.class, properties = "wallet.security.api-key=" + WalletControllerTest.API_KEY)
class WalletControllerTest {

    static final String API_KEY = "test-key";

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 11, 19, 12, 0, 0, 0, ZoneOffset.UTC);
    private static final String CREDIT_100 = """
            {"type":"CREDIT","amount":100.50,"description":"Depósito"}""";

    @TestConfiguration
    @EnableConfigurationProperties(ApiKeyProperties.class)
    static class Config {
    }

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private WalletService walletService;

    private final OracleErrorTranslator translator = new OracleErrorTranslator();

    @Test
    @DisplayName("POST /wallets devolve 201 com Location e saldo 0.00")
    void createWalletReturnsCreated() throws Exception {
        given(walletService.createWallet("Maria Silva", "12345678901"))
                .willReturn(new Wallet(1L, "Maria Silva", "12345678901", new BigDecimal("0.00"), NOW, NOW));

        mvc.perform(authorized(post("/wallets"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"holderName":"Maria Silva","holderDocument":"12345678901"}"""))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/wallets/1"))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(content().string(containsString("\"balance\":0.00")));
    }

    @Test
    @DisplayName("lançamento novo devolve 201 com Idempotent-Replayed: false")
    void newTransactionIsCreated() throws Exception {
        given(walletService.registerTransaction(any())).willReturn(result(false));

        mvc.perform(transaction("dep-1", CREDIT_100))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/wallets/1/transactions/42"))
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(jsonPath("$.id").value(42))
                .andExpect(jsonPath("$.balanceAfter").value(250.50))
                .andExpect(jsonPath("$.idempotencyKey").value("dep-1"));
    }

    @Test
    @DisplayName("retry devolve o mesmo 201 e o mesmo corpo, sinalizando Idempotent-Replayed: true")
    void replayedTransactionReturnsTheOriginalResult() throws Exception {
        given(walletService.registerTransaction(any())).willReturn(result(true));

        mvc.perform(transaction("dep-1", CREDIT_100))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.id").value(42));
    }

    @Test
    @DisplayName("ORA-20300 vira 422 INSUFFICIENT_FUNDS em application/problem+json")
    void insufficientFundsIsUnprocessable() throws Exception {
        given(walletService.registerTransaction(any())).willThrow(oracle(20300, "Saldo insuficiente para o débito"));

        mvc.perform(transaction("deb-1", """
                        {"type":"DEBIT","amount":1000}"""))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"))
                .andExpect(jsonPath("$.title").value("Saldo insuficiente"))
                .andExpect(jsonPath("$.detail").value("Saldo insuficiente para o débito"))
                .andExpect(jsonPath("$.status").value(422))
                .andExpect(jsonPath("$.instance").value("/wallets/1/transactions"))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("ORA-20200 vira 409 IDEMPOTENCY_KEY_CONFLICT")
    void idempotencyConflictIsConflict() throws Exception {
        given(walletService.registerTransaction(any())).willThrow(oracle(20200, "Chave já usada com outro payload"));

        mvc.perform(transaction("dep-1", CREDIT_100))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_CONFLICT"));
    }

    @Test
    @DisplayName("ORA-20201 vira 409 WALLET_BUSY com Retry-After")
    void busyWalletIsConflictWithRetryAfter() throws Exception {
        given(walletService.registerTransaction(any())).willThrow(oracle(20201, "Carteira 1 ocupada"));

        mvc.perform(transaction("dep-1", CREDIT_100))
                .andExpect(status().isConflict())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.code").value("WALLET_BUSY"));
    }

    @Test
    @DisplayName("ORA-20100 vira 404 WALLET_NOT_FOUND")
    void unknownWalletIsNotFound() throws Exception {
        given(walletService.registerTransaction(any())).willThrow(oracle(20100, "Carteira 1 não encontrada"));

        mvc.perform(transaction("dep-1", CREDIT_100))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("WALLET_NOT_FOUND"));
    }

    @Test
    @DisplayName("ORA-20004 vindo do banco vira 400 INVALID_AMOUNT")
    void invalidAmountFromDatabaseIsBadRequest() throws Exception {
        given(walletService.registerTransaction(any())).willThrow(oracle(20004, "Valor inválido"));

        mvc.perform(transaction("dep-1", CREDIT_100))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_AMOUNT"));
    }

    @Test
    @DisplayName("erro Oracle não mapeado vira 500 genérico, sem vazar a mensagem do banco")
    void unmappedOracleErrorIsInternalErrorWithoutLeakingDetails() throws Exception {
        given(walletService.registerTransaction(any())).willThrow(oracle(1, "unique constraint (WALLET_OWNER.PK) violated"));

        mvc.perform(transaction("dep-1", CREDIT_100))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value(not(containsString("ORA-"))))
                .andExpect(jsonPath("$.detail").value(not(containsString("WALLET_OWNER"))));
    }

    @Test
    @DisplayName("sem Idempotency-Key a requisição é rejeitada antes de chegar ao service")
    void missingIdempotencyKeyIsRejected() throws Exception {
        mvc.perform(authorized(post("/wallets/1/transactions"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CREDIT_100))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.detail").value(containsString("Idempotency-Key")));
        verifyNoInteractions(walletService);
    }

    @Test
    @DisplayName("valor negativo, com 3 casas e tipo ausente gera 400 listando os campos")
    void invalidPayloadListsEveryFieldError() throws Exception {
        mvc.perform(transaction("dep-1", """
                        {"amount":-1.999}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.errors[*].field").value(containsInAnyOrder("amount", "amount", "type")));
        verifyNoInteractions(walletService);
    }

    @Test
    @DisplayName("tipo de operação desconhecido gera 400 MALFORMED_REQUEST")
    void unknownOperationTypeIsMalformed() throws Exception {
        mvc.perform(transaction("dep-1", """
                        {"type":"PIX","amount":10}"""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        verifyNoInteractions(walletService);
    }

    @Test
    @DisplayName("Idempotency-Key fora do formato gera 400 apontando o header")
    void malformedIdempotencyKeyIsRejected() throws Exception {
        mvc.perform(transaction("chave com espaço", CREDIT_100))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("Idempotency-Key"));
        verifyNoInteractions(walletService);
    }

    @Test
    @DisplayName("extrato com size acima de 100 é rejeitado")
    void statementPageSizeIsLimited() throws Exception {
        mvc.perform(authorized(get("/wallets/1/transactions")).param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("size"));
    }

    @Test
    @DisplayName("extrato devolve página com metadados")
    void statementReturnsPage() throws Exception {
        given(walletService.getStatement(any()))
                .willReturn(new PageResult<>(List.of(result(false).transaction()), 0, 20, 1));

        mvc.perform(authorized(get("/wallets/1/transactions")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(42))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.totalPages").value(1));
    }

    @Test
    @DisplayName("sem X-API-Key a resposta é 401 no mesmo formato de erro")
    void missingApiKeyIsUnauthorized() throws Exception {
        mvc.perform(get("/wallets/1"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        verifyNoInteractions(walletService);
    }

    @Test
    @DisplayName("X-API-Key errada também é 401")
    void wrongApiKeyIsUnauthorized() throws Exception {
        mvc.perform(get("/wallets/1").header("X-API-Key", "outra"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(walletService);
    }

    @Test
    @DisplayName("falha inesperada vira 500 com mensagem genérica")
    void unexpectedFailureIsInternalError() throws Exception {
        given(walletService.getWallet(1L)).willThrow(new IllegalStateException("NullPointer em algum lugar"));

        mvc.perform(authorized(get("/wallets/1")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.detail").value(not(containsString("NullPointer"))));
    }

    @Test
    @DisplayName("WalletException de domínio usa o status do catálogo de erros")
    void domainExceptionUsesCatalogStatus() throws Exception {
        given(walletService.getTransaction(2L, 1L))
                .willThrow(new WalletException(ErrorCode.TRANSACTION_NOT_FOUND, "Transação 1 não encontrada na carteira 2"));

        mvc.perform(authorized(get("/wallets/2/transactions/1")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TRANSACTION_NOT_FOUND"));
    }

    private MockHttpServletRequestBuilder transaction(String idempotencyKey, String body) {
        return authorized(post("/wallets/1/transactions"))
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private static MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder request) {
        return request.header("X-API-Key", API_KEY);
    }

    private RuntimeException oracle(int code, String message) {
        return translator.translate(new UncategorizedSQLException("call", "{call pkg_wallet.register_transaction}",
                new SQLException("ORA-" + String.format("%05d", code) + ": " + message, "72000", code)));
    }

    private static TransactionResult result(boolean replayed) {
        TransactionCommand command = new TransactionCommand(1L, "dep-1", OperationType.CREDIT, new BigDecimal("100.50"),
                "Depósito");
        return new TransactionResult(new WalletTransaction(42L, command.walletId(), command.idempotencyKey(),
                command.type(), command.amount(), new BigDecimal("250.50"), command.description(), NOW), replayed);
    }
}
