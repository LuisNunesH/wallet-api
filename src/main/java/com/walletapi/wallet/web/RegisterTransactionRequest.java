package com.walletapi.wallet.web;

import java.math.BigDecimal;

import com.walletapi.wallet.domain.OperationType;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record RegisterTransactionRequest(
        @Schema(description = "CREDIT aumenta o saldo, DEBIT diminui", example = "CREDIT")
        @NotNull(message = "é obrigatório")
        OperationType type,

        @Schema(description = "Valor em BRL, maior que zero e com até 2 casas decimais", example = "100.50")
        @NotNull(message = "é obrigatório")
        @Positive(message = "deve ser maior que zero")
        @Digits(integer = 13, fraction = 2, message = "deve ter até 13 dígitos inteiros e 2 casas decimais")
        BigDecimal amount,

        @Schema(description = "Descrição livre do lançamento", example = "Depósito inicial", nullable = true)
        @Size(max = 140, message = "deve ter até 140 caracteres")
        String description) {
}
