package com.apiforge.ai.client;

import com.apiforge.common.error.ErrorCode;

/** Why a model call did not produce a usable answer. The message never contains prompt content. */
public class AiFailure extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public enum Kind {
        /** AI is turned off in the configuration. */
        DISABLED(ErrorCode.AI_UNAVAILABLE, false),
        /** Ollama cannot be reached (not running, wrong URL). */
        UNAVAILABLE(ErrorCode.AI_UNAVAILABLE, false),
        /** The configured model is not installed in Ollama. */
        MODEL_NOT_FOUND(ErrorCode.AI_MODEL_NOT_FOUND, false),
        /** No answer within the request timeout. Not retried: the call is expensive. */
        TIMEOUT(ErrorCode.AI_TIMEOUT, false),
        /** Ollama refused the request because it is busy; retried once after a pause. */
        OVERLOADED(ErrorCode.AI_BUSY, true),
        /** All local slots are in use by other AI requests. */
        BUSY(ErrorCode.AI_BUSY, false),
        /** The answer is not JSON or does not match the required schema; retried once. */
        INVALID_OUTPUT(ErrorCode.AI_INVALID_OUTPUT, true),
        /** The input is larger than the configured prompt limit; nothing was sent. */
        TOO_LARGE(ErrorCode.PAYLOAD_TOO_LARGE, false),
        /** Any other error reported by Ollama (e.g. not enough memory to load the model). */
        MODEL_ERROR(ErrorCode.AI_UNAVAILABLE, false);

        private final ErrorCode code;
        private final boolean retryable;

        Kind(ErrorCode code, boolean retryable) {
            this.code = code;
            this.retryable = retryable;
        }

        public ErrorCode code() {
            return code;
        }

        public boolean retryable() {
            return retryable;
        }
    }

    private final Kind kind;

    public AiFailure(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
