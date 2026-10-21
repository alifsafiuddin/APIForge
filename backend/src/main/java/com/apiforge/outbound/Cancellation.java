package com.apiforge.outbound;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Lets another thread abort the request in flight (run cancellation). Cancelling before a request
 * starts makes it fail immediately with {@code CANCELLED}.
 */
public final class Cancellation {

    private volatile boolean cancelled;
    private final AtomicReference<Runnable> abort = new AtomicReference<>();

    public void cancel() {
        cancelled = true;
        Runnable action = abort.get();
        if (action != null) {
            action.run();
        }
    }

    public boolean isCancelled() {
        return cancelled;
    }

    void onCancel(Runnable action) {
        abort.set(action);
        if (cancelled) {
            action.run();
        }
    }

    void clear() {
        abort.set(null);
    }
}
