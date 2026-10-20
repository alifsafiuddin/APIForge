package com.apiforge.common.error;

import java.util.List;

import com.apiforge.common.error.Problems.FieldErrorItem;

/**
 * A failure that maps directly to a documented problem response. The message is shown to the
 * client as {@code detail}, so it must never contain internal or sensitive information.
 */
public class ApiException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode code;
    private final transient List<FieldErrorItem> fieldErrors;

    public ApiException(ErrorCode code, String detail) {
        this(code, detail, List.of());
    }

    public ApiException(ErrorCode code, String detail, List<FieldErrorItem> fieldErrors) {
        super(detail);
        this.code = code;
        this.fieldErrors = List.copyOf(fieldErrors);
    }

    public ErrorCode code() {
        return code;
    }

    public List<FieldErrorItem> fieldErrors() {
        return fieldErrors;
    }

    /** The standard response for a missing resource, also used when the caller does not own it. */
    public static ApiException notFound(String resource) {
        return new ApiException(ErrorCode.NOT_FOUND, resource + " not found.");
    }
}
