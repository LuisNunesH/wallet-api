package com.walletapi.wallet;

import org.springframework.stereotype.Service;

import com.walletapi.error.ErrorCode;
import com.walletapi.error.WalletException;
import com.walletapi.wallet.domain.PageResult;
import com.walletapi.wallet.domain.StatementQuery;
import com.walletapi.wallet.domain.TransactionCommand;
import com.walletapi.wallet.domain.TransactionResult;
import com.walletapi.wallet.domain.Wallet;
import com.walletapi.wallet.domain.WalletTransaction;
import com.walletapi.wallet.persistence.WalletProcedureGateway;
import com.walletapi.wallet.persistence.WalletQueryRepository;

@Service
public class WalletService {

    private final WalletProcedureGateway procedures;
    private final WalletQueryRepository queries;

    public WalletService(WalletProcedureGateway procedures, WalletQueryRepository queries) {
        this.procedures = procedures;
        this.queries = queries;
    }

    public Wallet createWallet(String holderName, String holderDocument) {
        long walletId = procedures.createWallet(holderName, holderDocument);
        return getWallet(walletId);
    }

    public Wallet getWallet(long walletId) {
        return queries.findWallet(walletId).orElseThrow(() -> walletNotFound(walletId));
    }

    public TransactionResult registerTransaction(TransactionCommand command) {
        return procedures.registerTransaction(command);
    }

    public WalletTransaction getTransaction(long walletId, long transactionId) {
        return queries.findTransaction(walletId, transactionId)
                .orElseThrow(() -> new WalletException(ErrorCode.TRANSACTION_NOT_FOUND,
                        "Transação " + transactionId + " não encontrada na carteira " + walletId));
    }

    public PageResult<WalletTransaction> getStatement(StatementQuery query) {
        if (query.from() != null && query.to() != null && !query.from().isBefore(query.to())) {
            throw new WalletException(ErrorCode.VALIDATION_ERROR, "O parâmetro 'from' deve ser anterior a 'to'");
        }
        if (!queries.walletExists(query.walletId())) {
            throw walletNotFound(query.walletId());
        }
        return queries.findStatement(query);
    }

    private static WalletException walletNotFound(long walletId) {
        return new WalletException(ErrorCode.WALLET_NOT_FOUND, "Carteira " + walletId + " não encontrada");
    }
}
