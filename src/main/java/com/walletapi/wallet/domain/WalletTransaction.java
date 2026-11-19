package com.walletapi.wallet.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record WalletTransaction(
        long id,
        long walletId,
        String idempotencyKey,
        OperationType type,
        BigDecimal amount,
        BigDecimal balanceAfter,
        String description,
        OffsetDateTime createdAt) {
}
