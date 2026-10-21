package com.apiforge.spec.domain;

import java.util.List;

/** The document cannot be imported; {@link #messages()} explains why (at least one ERROR). */
public class SpecificationRejectedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient List<ImportMessage> messages;

    public SpecificationRejectedException(List<ImportMessage> messages) {
        super("Specification rejected with " + messages.size() + " message(s)");
        this.messages = List.copyOf(messages);
    }

    public SpecificationRejectedException(ImportMessage message) {
        this(List.of(message));
    }

    public List<ImportMessage> messages() {
        return messages;
    }
}
