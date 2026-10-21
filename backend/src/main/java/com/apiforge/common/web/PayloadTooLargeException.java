package com.apiforge.common.web;

import java.io.IOException;

/** Thrown while reading a request body that exceeds the configured size cap. */
public class PayloadTooLargeException extends IOException {

    private static final long serialVersionUID = 1L;

    public PayloadTooLargeException(long limit) {
        super("Request body exceeds " + limit + " bytes");
    }
}
