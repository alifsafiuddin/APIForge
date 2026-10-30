package com.apiforge.schema;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Evaluates {@code pattern} keywords from untrusted documents. Matching runs on a character
 * sequence with a read budget, so a catastrophically backtracking expression is abandoned
 * (result unknown) instead of blocking the request thread. JSON Schema patterns are not
 * anchored, hence {@link java.util.regex.Matcher#find()}.
 */
public final class GuardedPattern {

    /** Character reads allowed per match; generous for real patterns on short generated strings. */
    public static final int READ_BUDGET = 200_000;
    private static final int MAX_CACHED = 256;

    private final Map<String, Optional<Pattern>> compiled = new HashMap<>();

    /** @return whether {@code input} matches, or empty when the pattern is invalid or too expensive */
    public Optional<Boolean> matches(String regex, String input) {
        Optional<Pattern> pattern = compile(regex);
        if (pattern.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(pattern.get().matcher(new BudgetedSequence(input, new int[] {0}, 0, input.length())).find());
        } catch (BudgetExceededException ex) {
            return Optional.empty();
        }
    }

    public Optional<Pattern> compile(String regex) {
        Optional<Pattern> cached = compiled.get(regex);
        if (cached != null) {
            return cached;
        }
        Optional<Pattern> pattern;
        try {
            pattern = Optional.of(Pattern.compile(regex));
        } catch (PatternSyntaxException ex) {
            pattern = Optional.empty();
        }
        if (compiled.size() < MAX_CACHED) {
            compiled.put(regex, pattern);
        }
        return pattern;
    }

    private static final class BudgetExceededException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public BudgetExceededException() {
            super(null, null, false, false);
        }
    }

    /** A view of a string that counts character reads across all of its subsequences. */
    private record BudgetedSequence(String text, int[] reads, int start, int end) implements CharSequence {

        @Override
        public char charAt(int index) {
            if (++reads[0] > READ_BUDGET) {
                throw new BudgetExceededException();
            }
            return text.charAt(start + index);
        }

        @Override
        public int length() {
            return end - start;
        }

        @Override
        public CharSequence subSequence(int from, int to) {
            return new BudgetedSequence(text, reads, start + from, start + to);
        }

        @Override
        public String toString() {
            return text.substring(start, end);
        }
    }
}
