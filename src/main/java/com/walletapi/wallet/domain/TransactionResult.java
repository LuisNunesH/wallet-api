package com.walletapi.wallet.domain;

public record TransactionResult(WalletTransaction transaction, boolean replayed) {
}
