package com.walletapi.wallet.domain;

import java.math.BigDecimal;

public record TransactionCommand(
        long walletId,
        String idempotencyKey,
        OperationType type,
        BigDecimal amount,
        String description) {
}
