package com.walletapi.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.walletapi.security.ApiKeyFilter;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;

@Configuration
public class OpenApiConfig {

    private static final String API_KEY_SCHEME = "apiKey";

    @Bean
    public OpenAPI walletOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Wallet API")
                        .version("v1")
                        .description("Carteira digital em BRL. Regras de saldo, concorrência e idempotência "
                                + "são garantidas pelo Oracle Database (PKG_WALLET). Erros seguem RFC 9457 "
                                + "(application/problem+json) com o campo adicional 'code'."))
                .components(new Components().addSecuritySchemes(API_KEY_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER)
                        .name(ApiKeyFilter.API_KEY_HEADER)))
                .addSecurityItem(new SecurityRequirement().addList(API_KEY_SCHEME));
    }
}
