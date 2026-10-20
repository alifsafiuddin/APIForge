package com.apiforge.common.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import com.apiforge.common.error.ApiException;
import com.apiforge.common.error.ErrorCode;

/**
 * Translates client sort parameters into entity properties through an explicit allow-list, so
 * clients cannot sort by arbitrary (or non-existent) fields, and applies a default order.
 */
public final class Pagination {

    private Pagination() {
    }

    /**
     * @param allowedSorts API sort name to entity property name
     * @param defaultSort  applied when the client sends no sort
     */
    public static Pageable sanitize(Pageable requested, Map<String, String> allowedSorts, Sort defaultSort) {
        if (requested.getSort().isUnsorted()) {
            return PageRequest.of(requested.getPageNumber(), requested.getPageSize(), defaultSort);
        }
        List<Sort.Order> orders = new ArrayList<>();
        for (Sort.Order order : requested.getSort()) {
            String property = allowedSorts.get(order.getProperty());
            if (property == null) {
                throw new ApiException(ErrorCode.VALIDATION_ERROR,
                        "Unsupported sort property '%s'. Allowed: %s.".formatted(
                                truncate(order.getProperty()), String.join(", ", new TreeSet<>(allowedSorts.keySet()))));
            }
            orders.add(new Sort.Order(order.getDirection(), property));
        }
        return PageRequest.of(requested.getPageNumber(), requested.getPageSize(), Sort.by(orders));
    }

    private static String truncate(String value) {
        return value.length() > 40 ? value.substring(0, 40) + "…" : value;
    }
}
