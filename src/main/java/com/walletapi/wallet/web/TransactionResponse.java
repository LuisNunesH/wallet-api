package com.walletapi.wallet.web;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import com.walletapi.wallet.domain.OperationType;
import com.walletapi.wallet.domain.WalletTransaction;

import io.swagger.v3.oas.annotations.media.Schema;

public record TransactionResponse(
        @Schema(example = "42") long id,
        @Schema(example = "1") long walletId,
        @Schema(example = "3f1c9a52-7d1e-4f0a-9b8e-2c6d5e4f3a21") String idempotencyKey,
        OperationType type,
        @Schema(example = "100.50") BigDecimal amount,
        @Schema(description = "Saldo da carteira imediatamente após este lançamento", example = "250.50")
        BigDecimal balanceAfter,
        @Schema(nullable = true) String description,
        OffsetDateTime createdAt) {

    static TransactionResponse from(WalletTransaction transaction) {
        return new TransactionResponse(
                transaction.id(),
                transaction.walletId(),
                transaction.idempotencyKey(),
                transaction.type(),
                transaction.amount(),
                transaction.balanceAfter(),
                transaction.description(),
                transaction.createdAt());
    }
}
