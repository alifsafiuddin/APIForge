package com.apiforge.spec.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.apiforge.spec.domain.ImportMessage;
import com.apiforge.spec.domain.ImportMessage.Code;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.OpenAPIV3Parser;
import io.swagger.v3.parser.core.models.ParseOptions;
import io.swagger.v3.parser.core.models.SwaggerParseResult;

/**
 * Wraps swagger-parser. It receives a document that already passed {@link ReferenceGuard}
 * (local references only) as a JSON string, so the library never parses raw YAML from the user
 * and has nothing external to resolve. Its free-text messages are mapped to coded, readable
 * {@link ImportMessage}s.
 */
final class OpenApiParserAdapter {

    record Parsed(OpenAPI openApi, List<ImportMessage> messages) {
    }

    private static final Pattern MISSING = Pattern.compile("^attribute (.+) is missing$");
    private static final Pattern UNEXPECTED = Pattern.compile("^attribute (.+) is unexpected$");
    private static final Pattern WRONG_TYPE = Pattern.compile("^attribute (.+) is not of type `?([^`]+)`?$");
    private static final Pattern UNDECLARED_PATH_PARAM = Pattern.compile(
            "^attribute (.+)\\. Declared path parameter (.+) needs to be defined as a path parameter in path or operation level$");
    private static final Pattern ATTRIBUTE = Pattern.compile("^attribute (\\S+) (.+)$");

    private OpenApiParserAdapter() {
    }

    static Parsed parse(String json) {
        ParseOptions options = new ParseOptions();
        options.setResolve(true);
        options.setResolveFully(false);
        options.setValidateInternalRefs(true);
        options.setValidateExternalRefs(false);
        options.setSafelyResolveURL(true);

        SwaggerParseResult result = new OpenAPIV3Parser().readContents(json, null, options);
        List<ImportMessage> messages = new ArrayList<>();
        if (result.getMessages() != null) {
            for (String message : result.getMessages()) {
                messages.add(classify(message));
            }
        }
        return new Parsed(result.getOpenAPI(), messages);
    }

    static ImportMessage classify(String raw) {
        String message = raw == null ? "" : raw.strip();
        Matcher matcher = UNDECLARED_PATH_PARAM.matcher(message);
        if (matcher.matches()) {
            return ImportMessage.error(Code.UNDECLARED_PATH_PARAMETER,
                    "Path parameter '%s' appears in the path but is not declared as a parameter (in: path)."
                            .formatted(matcher.group(2)),
                    matcher.group(1));
        }
        matcher = MISSING.matcher(message);
        if (matcher.matches()) {
            String[] parts = splitAttribute(matcher.group(1));
            return ImportMessage.error(Code.MISSING_FIELD, "Required field '%s' is missing.".formatted(parts[1]),
                    parts[0]);
        }
        matcher = WRONG_TYPE.matcher(message);
        if (matcher.matches()) {
            String[] parts = splitAttribute(matcher.group(1));
            return ImportMessage.error(Code.INVALID_FIELD,
                    "Field '%s' must be of type %s.".formatted(parts[1], matcher.group(2)), parts[0]);
        }
        matcher = UNEXPECTED.matcher(message);
        if (matcher.matches()) {
            String[] parts = splitAttribute(matcher.group(1));
            return ImportMessage.warning(Code.UNEXPECTED_FIELD,
                    "Field '%s' is not defined by OpenAPI and is ignored.".formatted(parts[1]), parts[0]);
        }
        matcher = ATTRIBUTE.matcher(message);
        if (matcher.matches() && message.toLowerCase(java.util.Locale.ROOT).contains("invalid")) {
            return ImportMessage.error(Code.INVALID_FIELD, message, matcher.group(1));
        }
        return ImportMessage.warning(Code.PARSER_NOTICE, message, null);
    }

    /** "paths.'/pets'(get).responses" becomes ["paths.'/pets'(get)", "responses"]. */
    private static String[] splitAttribute(String attribute) {
        int depth = 0;
        boolean quoted = false;
        for (int i = attribute.length() - 1; i >= 0; i--) {
            char c = attribute.charAt(i);
            if (c == '\'') {
                quoted = !quoted;
            } else if (!quoted && c == ')') {
                depth++;
            } else if (!quoted && c == '(') {
                depth--;
            } else if (!quoted && depth == 0 && c == '.') {
                return new String[] { attribute.substring(0, i), attribute.substring(i + 1) };
            }
        }
        return new String[] { "(document root)", attribute };
    }
}
