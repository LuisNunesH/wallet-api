package com.walletapi.wallet.domain;

import java.time.OffsetDateTime;

public record StatementQuery(
        long walletId,
        OffsetDateTime from,
        OffsetDateTime to,
        int page,
        int size) {

    public long offset() {
        return (long) page * size;
    }
}
