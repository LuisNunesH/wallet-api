package com.walletapi.wallet.web;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import com.walletapi.wallet.domain.Wallet;

import io.swagger.v3.oas.annotations.media.Schema;

public record WalletResponse(
        @Schema(example = "1") long id,
        @Schema(example = "Maria Silva") String holderName,
        @Schema(example = "12345678901", nullable = true) String holderDocument,
        @Schema(example = "150.75") BigDecimal balance,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    static WalletResponse from(Wallet wallet) {
        return new WalletResponse(
                wallet.id(),
                wallet.holderName(),
                wallet.holderDocument(),
                wallet.balance(),
                wallet.createdAt(),
                wallet.updatedAt());
    }
}
