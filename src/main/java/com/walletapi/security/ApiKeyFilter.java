package com.walletapi.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import com.walletapi.error.ErrorCode;
import com.walletapi.error.WalletException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class ApiKeyFilter extends OncePerRequestFilter {

    public static final String API_KEY_HEADER = "X-API-Key";

    private static final List<String> PUBLIC_PATHS = List.of(
            "/actuator/health",
            "/v3/api-docs",
            "/swagger-ui",
            "/swagger-ui.html");

    private final byte[] expectedKey;
    private final HandlerExceptionResolver exceptionResolver;

    public ApiKeyFilter(ApiKeyProperties properties,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) {
        this.expectedKey = properties.apiKey().getBytes(StandardCharsets.UTF_8);
        this.exceptionResolver = exceptionResolver;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        return PUBLIC_PATHS.stream().anyMatch(publicPath -> path.equals(publicPath) || path.startsWith(publicPath + "/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String providedKey = request.getHeader(API_KEY_HEADER);
        if (providedKey == null || !MessageDigest.isEqual(expectedKey, providedKey.getBytes(StandardCharsets.UTF_8))) {
            exceptionResolver.resolveException(request, response, null,
                    new WalletException(ErrorCode.UNAUTHORIZED, "Header " + API_KEY_HEADER + " ausente ou inválido"));
            return;
        }
        chain.doFilter(request, response);
    }
}
