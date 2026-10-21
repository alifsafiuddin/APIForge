package com.apiforge.spec.domain;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A finding about an imported document. ERROR rejects the import; WARNING and INFO are stored
 * with the version and shown in the explorer.
 *
 * @param location where in the document the finding applies (for example
 *                 {@code paths["/pets/{id}"].get} or {@code line 12, column 3}), or {@code null}
 */
public record ImportMessage(Severity severity, Code code, String message, String location) {

    public enum Severity {
        ERROR, WARNING, INFO
    }

    /** Stable identifiers clients may branch on; the text in {@code message} may change. */
    public enum Code {
        EMPTY_DOCUMENT,
        INVALID_ENCODING,
        PARSE_ERROR,
        DUPLICATE_KEY,
        LIMIT_EXCEEDED,
        NOT_AN_OBJECT,
        UNSUPPORTED_VERSION,
        MISSING_FIELD,
        INVALID_FIELD,
        UNEXPECTED_FIELD,
        UNDECLARED_PATH_PARAMETER,
        EXTERNAL_REFERENCE,
        UNRESOLVED_REFERENCE,
        INVALID_REFERENCE,
        DUPLICATE_OPERATION_ID,
        AMBIGUOUS_PATH,
        UNSUPPORTED_FEATURE,
        NO_OPERATIONS,
        NO_SERVERS,
        IDENTICAL_CONTENT,
        PARSER_NOTICE
    }

    public static ImportMessage error(Code code, String message, String location) {
        return new ImportMessage(Severity.ERROR, code, message, location);
    }

    public static ImportMessage warning(Code code, String message, String location) {
        return new ImportMessage(Severity.WARNING, code, message, location);
    }

    public static ImportMessage info(Code code, String message, String location) {
        return new ImportMessage(Severity.INFO, code, message, location);
    }

    public boolean isError() {
        return severity == Severity.ERROR;
    }

    /** JSON-friendly form used for persistence (jsonb) and problem responses. */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("severity", severity.name());
        map.put("code", code.name());
        map.put("message", message);
        if (location != null) {
            map.put("location", location);
        }
        return map;
    }
}
