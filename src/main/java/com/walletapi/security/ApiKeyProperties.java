package com.walletapi.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;

@Validated
@ConfigurationProperties(prefix = "wallet.security")
public record ApiKeyProperties(@NotBlank(message = "defina a variável de ambiente API_KEY") String apiKey) {
}
