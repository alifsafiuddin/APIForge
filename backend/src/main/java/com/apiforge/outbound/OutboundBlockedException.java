package com.apiforge.outbound;

/**
 * A destination was refused by the outbound policy before any connection was made. The message
 * is safe to show to the user: it names the rule, never internal addresses.
 */
public class OutboundBlockedException extends Exception {

    private static final long serialVersionUID = 1L;

    public enum Reason {
        INVALID_URL, UNSUPPORTED_PROTOCOL, CREDENTIALS_IN_URL, NON_CANONICAL_ADDRESS, BLOCKED_HOSTNAME,
        BLOCKED_PORT, BLOCKED_ADDRESS, DNS_FAILURE, UNEXPECTED_PEER
    }

    private final Reason reason;

    public OutboundBlockedException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
