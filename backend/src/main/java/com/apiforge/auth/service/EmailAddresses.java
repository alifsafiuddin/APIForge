package com.apiforge.auth.service;

import java.util.Locale;

final class EmailAddresses {

    private EmailAddresses() {
    }

    /** Canonical stored form: trimmed and lower-cased (the column is also case-insensitive). */
    static String normalize(String email) {
        return email == null ? "" : email.strip().toLowerCase(Locale.ROOT);
    }
}
