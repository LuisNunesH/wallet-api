package com.walletapi.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.DisplayName;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.servlet.HandlerExceptionResolver;

class ApiKeyFilterTest {

    private final ApiKeyFilter filter = new ApiKeyFilter(new ApiKeyProperties("chave"), mock(HandlerExceptionResolver.class));

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health", "/actuator/health/readiness", "/v3/api-docs", "/v3/api-docs/swagger-config",
            "/swagger-ui.html", "/swagger-ui/index.html"})
    @DisplayName("health e documentação ficam públicos")
    void publicPathsSkipTheFilter(String path) {
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", path))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"/wallets", "/wallets/1", "/wallets/1/transactions", "/actuator/info", "/actuator/healthz",
            "/swagger-uix"})
    @DisplayName("todo o resto exige API key, inclusive caminhos parecidos com os públicos")
    void everythingElseIsProtected(String path) {
        assertThat(filter.shouldNotFilter(new MockHttpServletRequest("GET", path))).isFalse();
    }
}
