package com.walletapi.wallet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.walletapi.error.ErrorCode;
import com.walletapi.error.WalletException;
import com.walletapi.wallet.domain.OperationType;
import com.walletapi.wallet.domain.PageResult;
import com.walletapi.wallet.domain.StatementQuery;
import com.walletapi.wallet.domain.TransactionCommand;
import com.walletapi.wallet.domain.Wallet;
import com.walletapi.wallet.domain.WalletTransaction;
import com.walletapi.wallet.persistence.WalletProcedureGateway;
import com.walletapi.wallet.persistence.WalletQueryRepository;

@ExtendWith(MockitoExtension.class)
class WalletServiceTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 11, 19, 12, 0, 0, 0, ZoneOffset.UTC);

    @Mock
    private WalletProcedureGateway procedures;

    @Mock
    private WalletQueryRepository queries;

    @InjectMocks
    private WalletService service;

    @Test
    @DisplayName("cria a carteira pelo package e devolve o estado lido da view")
    void createWalletReadsBackFromTheView() {
        Wallet stored = new Wallet(10L, "Maria Silva", null, new BigDecimal("0.00"), NOW, NOW);
        given(procedures.createWallet("Maria Silva", null)).willReturn(10L);
        given(queries.findWallet(10L)).willReturn(Optional.of(stored));

        assertThat(service.createWallet("Maria Silva", null)).isEqualTo(stored);
    }

    @Test
    @DisplayName("consulta de carteira inexistente gera WALLET_NOT_FOUND")
    void getWalletFailsWhenWalletDoesNotExist() {
        given(queries.findWallet(99L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getWallet(99L))
                .isInstanceOfSatisfying(WalletException.class,
                        exception -> assertThat(exception.code()).isEqualTo(ErrorCode.WALLET_NOT_FOUND));
    }

    @Test
    @DisplayName("saldo insuficiente vindo do banco é propagado sem ser mascarado")
    void insufficientFundsFromTheDatabaseIsPropagated() {
        TransactionCommand debit = new TransactionCommand(1L, "deb-1", OperationType.DEBIT, new BigDecimal("50.00"), null);
        given(procedures.registerTransaction(debit))
                .willThrow(new WalletException(ErrorCode.INSUFFICIENT_FUNDS, "Saldo insuficiente para o débito"));

        assertThatThrownBy(() -> service.registerTransaction(debit))
                .isInstanceOfSatisfying(WalletException.class, exception -> {
                    assertThat(exception.code()).isEqualTo(ErrorCode.INSUFFICIENT_FUNDS);
                    assertThat(exception.getMessage()).isEqualTo("Saldo insuficiente para o débito");
                });
    }

    @Test
    @DisplayName("extrato com 'from' igual ou posterior a 'to' é rejeitado sem consultar o banco")
    void statementWithInvertedPeriodIsRejectedBeforeHittingTheDatabase() {
        StatementQuery query = new StatementQuery(1L, NOW, NOW, 0, 20);

        assertThatThrownBy(() -> service.getStatement(query))
                .isInstanceOfSatisfying(WalletException.class,
                        exception -> assertThat(exception.code()).isEqualTo(ErrorCode.VALIDATION_ERROR));
        verifyNoInteractions(queries);
    }

    @Test
    @DisplayName("extrato de carteira inexistente gera 404 em vez de lista vazia")
    void statementOfUnknownWalletIsNotFound() {
        StatementQuery query = new StatementQuery(42L, null, null, 0, 20);
        given(queries.walletExists(42L)).willReturn(false);

        assertThatThrownBy(() -> service.getStatement(query))
                .isInstanceOfSatisfying(WalletException.class,
                        exception -> assertThat(exception.code()).isEqualTo(ErrorCode.WALLET_NOT_FOUND));
        verify(queries, never()).findStatement(any());
    }

    @Test
    @DisplayName("extrato de carteira existente é devolvido como está")
    void statementOfExistingWalletIsReturned() {
        StatementQuery query = new StatementQuery(1L, NOW.minusDays(1), NOW, 0, 20);
        PageResult<WalletTransaction> page = new PageResult<>(List.of(), 0, 20, 0);
        given(queries.walletExists(1L)).willReturn(true);
        given(queries.findStatement(query)).willReturn(page);

        assertThat(service.getStatement(query)).isSameAs(page);
    }

    @Test
    @DisplayName("lançamento de outra carteira gera TRANSACTION_NOT_FOUND")
    void transactionOfAnotherWalletIsNotFound() {
        given(queries.findTransaction(2L, 1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.getTransaction(2L, 1L))
                .isInstanceOfSatisfying(WalletException.class,
                        exception -> assertThat(exception.code()).isEqualTo(ErrorCode.TRANSACTION_NOT_FOUND));
    }
}
