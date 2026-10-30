package com.apiforge.auth.service;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToLongFunction;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.apiforge.auth.repository.UserRepository;

/**
 * When each user's credentials last changed (epoch milliseconds), so sessions signed in earlier can
 * be ended without a database query per request. Single instance, like the sessions themselves
 * (ADR-008); filled lazily and updated by {@link AccountService}.
 */
@Component
public class CredentialEpochs {

    /** For deleted users: every session signed in before the end of time is invalid. */
    public static final long DELETED = Long.MAX_VALUE;
    private static final int MAX_ENTRIES = 50_000;

    private final ToLongFunction<UUID> lookup;
    private final Map<UUID, Long> epochs = new ConcurrentHashMap<>();

    @Autowired
    public CredentialEpochs(UserRepository users) {
        this(userId -> users.findById(userId).map(user -> user.getCredentialsChangedAt() == null ? 0L
                : user.getCredentialsChangedAt().toEpochMilli()).orElse(DELETED));
    }

    /** @param lookup epoch milliseconds of the last change, 0 if never, {@link #DELETED} if no such user */
    public CredentialEpochs(ToLongFunction<UUID> lookup) {
        this.lookup = lookup;
    }

    /** Sessions of {@code userId} signed in before this moment are no longer valid. */
    public long changedAt(UUID userId) {
        Long known = epochs.get(userId);
        if (known != null) {
            return known;
        }
        long loaded = lookup.applyAsLong(userId);
        if (epochs.size() >= MAX_ENTRIES) {
            epochs.clear();
        }
        epochs.put(userId, loaded);
        return loaded;
    }

    void changed(UUID userId, Instant at) {
        epochs.put(userId, at.toEpochMilli());
    }

    void deleted(UUID userId) {
        epochs.put(userId, DELETED);
    }
}
