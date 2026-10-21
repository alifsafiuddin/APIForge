package com.apiforge.coverage.service;

import java.util.List;

/**
 * What a generated scenario checks, derived from its scenario key ({@code scope.kind:target},
 * e.g. {@code body.too-long:/name}, {@code query.missing:limit}, {@code auth.none}).
 */
public enum ScenarioKind {

    /** A valid request ({@code happy.*}). */
    VALID_REQUEST("Valid request", false, false),
    /** A value exactly at a documented limit that must be accepted ({@code at-minimum}, {@code at-max-length}, ...). */
    ACCEPTED_BOUNDARY("Accepted boundary value", true, false),
    /** Missing or invalid credentials. */
    AUTHENTICATION("Missing or invalid credentials", false, true),
    /** A required body, field, query parameter or header left out. */
    MISSING_REQUIRED("Missing required input", true, true),
    /** A value of the wrong JSON type. */
    WRONG_TYPE("Wrong type", true, true),
    /** A number, length or item count outside its documented range. */
    OUT_OF_RANGE("Out-of-range value", true, true),
    /** A value outside an enum. */
    INVALID_VALUE("Value outside the allowed set", true, true),
    /** A body with an undocumented media type. */
    UNSUPPORTED_CONTENT_TYPE("Unsupported content type", true, true),
    /** A key this version of the generator does not produce. */
    OTHER("Other", false, false);

    /** The negative kinds the dashboard checks for, in display order. */
    public static final List<ScenarioKind> NEGATIVE = List.of(AUTHENTICATION, MISSING_REQUIRED, WRONG_TYPE, OUT_OF_RANGE,
            INVALID_VALUE, UNSUPPORTED_CONTENT_TYPE);

    private final String label;
    private final boolean constraint;
    private final boolean negative;

    ScenarioKind(String label, boolean constraint, boolean negative) {
        this.label = label;
        this.constraint = constraint;
        this.negative = negative;
    }

    public String label() {
        return label;
    }

    /** Whether the scenario probes one rule of the request contract (counted as a known constraint). */
    public boolean constraint() {
        return constraint;
    }

    /** Whether the scenario expects the API to reject the request. */
    public boolean negative() {
        return negative;
    }

    /** The kind of a scenario key; {@code null} keys (tests written by hand) have no kind. */
    public static ScenarioKind of(String scenarioKey) {
        if (scenarioKey == null) {
            return null;
        }
        if (scenarioKey.startsWith("happy.")) {
            return VALID_REQUEST;
        }
        if (scenarioKey.startsWith("auth.")) {
            return AUTHENTICATION;
        }
        if (scenarioKey.equals("content-type.unsupported")) {
            return UNSUPPORTED_CONTENT_TYPE;
        }
        if (scenarioKey.equals("body.missing")) {
            return MISSING_REQUIRED;
        }
        int dot = scenarioKey.indexOf('.');
        int colon = scenarioKey.indexOf(':');
        if (dot < 0 || colon < dot) {
            return OTHER;
        }
        String kind = scenarioKey.substring(dot + 1, colon);
        if (kind.startsWith("at-")) {
            return ACCEPTED_BOUNDARY;
        }
        return switch (kind) {
            case "missing" -> MISSING_REQUIRED;
            case "type" -> WRONG_TYPE;
            case "enum" -> INVALID_VALUE;
            case "below-minimum", "above-maximum", "too-short", "too-long", "too-few-items", "too-many-items" -> OUT_OF_RANGE;
            default -> OTHER;
        };
    }
}
