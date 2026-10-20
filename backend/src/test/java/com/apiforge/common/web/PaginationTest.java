package com.apiforge.common.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import com.apiforge.common.error.ApiException;
import com.apiforge.common.error.ErrorCode;

class PaginationTest {

    private static final Map<String, String> ALLOWED = Map.of("name", "name", "created", "createdAt");
    private static final Sort DEFAULT = Sort.by(Sort.Order.desc("createdAt"));

    @Test
    void appliesDefaultSortWhenNoneRequested() {
        Pageable result = Pagination.sanitize(PageRequest.of(2, 15), ALLOWED, DEFAULT);

        assertThat(result.getPageNumber()).isEqualTo(2);
        assertThat(result.getPageSize()).isEqualTo(15);
        assertThat(result.getSort()).isEqualTo(DEFAULT);
    }

    @Test
    void mapsApiSortNamesToEntityProperties() {
        Pageable result = Pagination.sanitize(PageRequest.of(0, 10, Sort.by(Sort.Order.asc("created"))), ALLOWED,
                DEFAULT);

        assertThat(result.getSort()).isEqualTo(Sort.by(Sort.Order.asc("createdAt")));
    }

    @Test
    void rejectsPropertiesOutsideTheAllowList() {
        Pageable request = PageRequest.of(0, 10, Sort.by("ownerId"));

        assertThatThrownBy(() -> Pagination.sanitize(request, ALLOWED, DEFAULT))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.code()).isEqualTo(ErrorCode.VALIDATION_ERROR);
                    assertThat(ex.getMessage()).contains("ownerId").contains("created, name");
                });
    }
}
