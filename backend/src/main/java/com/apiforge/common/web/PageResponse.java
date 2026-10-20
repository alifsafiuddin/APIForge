package com.apiforge.common.web;

import java.util.List;
import java.util.function.Function;

import org.springframework.data.domain.Page;

/**
 * Stable JSON shape for paged collections:
 * {@code {"content": [...], "page": {"number", "size", "totalElements", "totalPages"}}}.
 */
public record PageResponse<T>(List<T> content, PageMetadata page) {

    public record PageMetadata(int number, int size, long totalElements, int totalPages) {
    }

    public static <T> PageResponse<T> of(Page<T> page) {
        return new PageResponse<>(page.getContent(),
                new PageMetadata(page.getNumber(), page.getSize(), page.getTotalElements(), page.getTotalPages()));
    }

    public static <S, T> PageResponse<T> of(Page<S> page, Function<S, T> mapper) {
        return of(page.map(mapper));
    }
}
