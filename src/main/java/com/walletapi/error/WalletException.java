package com.walletapi.error;

public class WalletException extends RuntimeException {

    private final ErrorCode code;

    public WalletException(ErrorCode code, String detail) {
        super(detail);
        this.code = code;
    }

    public WalletException(ErrorCode code, String detail, Throwable cause) {
        super(detail, cause);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }
}
