package com.walletapi.wallet.web;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateWalletRequest(
        @Schema(description = "Nome do titular", example = "Maria Silva")
        @NotBlank(message = "é obrigatório")
        @Size(max = 150, message = "deve ter até 150 caracteres")
        String holderName,

        @Schema(description = "CPF (11 dígitos) ou CNPJ (14 dígitos), somente números", example = "12345678901",
                nullable = true)
        @Pattern(regexp = "^([0-9]{11}|[0-9]{14})$", message = "deve conter 11 (CPF) ou 14 (CNPJ) dígitos")
        String holderDocument) {
}
