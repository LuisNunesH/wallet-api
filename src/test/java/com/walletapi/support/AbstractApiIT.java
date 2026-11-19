package com.walletapi.support;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import com.walletapi.wallet.web.WalletResponse;

import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class AbstractApiIT {

    protected static final String API_KEY = "integration-test-key";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    private int port;

    private RestClient client;

    @DynamicPropertySource
    static void oracleProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", OracleTestDatabase::jdbcUrl);
        registry.add("spring.datasource.username", () -> OracleTestDatabase.APP);
        registry.add("spring.datasource.password", OracleTestDatabase::appPassword);
        registry.add("wallet.security.api-key", () -> API_KEY);
    }

    protected record HttpResult(int status, HttpHeaders headers, String body) {

        public <T> T as(Class<T> type) {
            return JSON.readValue(body, type);
        }

        @SuppressWarnings("unchecked")
        public Map<String, Object> asMap() {
            return JSON.readValue(body, Map.class);
        }
    }

    protected RestClient client() {
        if (client == null) {
            client = RestClient.builder()
                    .baseUrl("http://localhost:" + port)
                    .defaultHeader("X-API-Key", API_KEY)
                    .defaultStatusHandler(HttpStatusCode::isError, (request, response) -> {
                    })
                    .build();
        }
        return client;
    }

    protected HttpResult get(String uri) {
        return toResult(client().get().uri(uri).retrieve().toEntity(String.class));
    }

    protected HttpResult postJson(String uri, Map<String, Object> body, String idempotencyKey) {
        RestClient.RequestBodySpec request = client().post().uri(uri).contentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return toResult(request.body(JSON.writeValueAsString(body)).retrieve().toEntity(String.class));
    }

    protected WalletResponse createWallet(String holderName) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("holderName", holderName);
        HttpResult result = postJson("/wallets", body, null);
        if (result.status() != 201) {
            throw new IllegalStateException("falha ao criar carteira: " + result);
        }
        return result.as(WalletResponse.class);
    }

    protected HttpResult transaction(long walletId, String idempotencyKey, String type, String amount) {
        return transaction(walletId, idempotencyKey, type, amount, null);
    }

    protected HttpResult transaction(long walletId, String idempotencyKey, String type, String amount,
            String description) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", type);
        body.put("amount", new BigDecimal(amount));
        if (description != null) {
            body.put("description", description);
        }
        return postJson("/wallets/" + walletId + "/transactions", body, idempotencyKey);
    }

    private static HttpResult toResult(ResponseEntity<String> response) {
        return new HttpResult(response.getStatusCode().value(), response.getHeaders(), response.getBody());
    }
}
