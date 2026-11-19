package com.walletapi.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

    VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Requisição inválida"),
    MALFORMED_REQUEST(HttpStatus.BAD_REQUEST, "Corpo da requisição malformado"),
    INVALID_HOLDER_NAME(HttpStatus.BAD_REQUEST, "Nome do titular inválido"),
    INVALID_HOLDER_DOCUMENT(HttpStatus.BAD_REQUEST, "Documento do titular inválido"),
    INVALID_OPERATION_TYPE(HttpStatus.BAD_REQUEST, "Tipo de operação inválido"),
    INVALID_AMOUNT(HttpStatus.BAD_REQUEST, "Valor inválido"),
    INVALID_IDEMPOTENCY_KEY(HttpStatus.BAD_REQUEST, "Chave de idempotência inválida"),
    INVALID_DESCRIPTION(HttpStatus.BAD_REQUEST, "Descrição inválida"),
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED, "Não autorizado"),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "Recurso não encontrado"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "Método não permitido"),
    NOT_ACCEPTABLE(HttpStatus.NOT_ACCEPTABLE, "Formato de resposta não suportado"),
    UNSUPPORTED_MEDIA_TYPE(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Tipo de conteúdo não suportado"),
    WALLET_NOT_FOUND(HttpStatus.NOT_FOUND, "Carteira não encontrada"),
    TRANSACTION_NOT_FOUND(HttpStatus.NOT_FOUND, "Transação não encontrada"),
    IDEMPOTENCY_KEY_CONFLICT(HttpStatus.CONFLICT, "Conflito de chave de idempotência"),
    WALLET_BUSY(HttpStatus.CONFLICT, "Carteira ocupada"),
    INSUFFICIENT_FUNDS(HttpStatus.UNPROCESSABLE_CONTENT, "Saldo insuficiente"),
    BALANCE_LIMIT_EXCEEDED(HttpStatus.UNPROCESSABLE_CONTENT, "Limite de saldo excedido"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Erro interno");

    private final HttpStatus status;
    private final String title;

    ErrorCode(HttpStatus status, String title) {
        this.status = status;
        this.title = title;
    }

    public HttpStatus status() {
        return status;
    }

    public String title() {
        return title;
    }
}
