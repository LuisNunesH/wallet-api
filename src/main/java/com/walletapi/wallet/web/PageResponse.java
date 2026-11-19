package com.walletapi.wallet.web;

import java.util.List;
import java.util.function.Function;

import com.walletapi.wallet.domain.PageResult;

public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    static <S, T> PageResponse<T> from(PageResult<S> result, Function<S, T> mapper) {
        PageResult<T> mapped = result.map(mapper);
        return new PageResponse<>(mapped.content(), mapped.page(), mapped.size(), mapped.totalElements(),
                mapped.totalPages());
    }
}
