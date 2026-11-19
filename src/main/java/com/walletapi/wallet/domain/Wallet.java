package com.walletapi.wallet.domain;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record Wallet(
        long id,
        String holderName,
        String holderDocument,
        BigDecimal balance,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
