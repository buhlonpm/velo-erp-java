package com.velo.common.exception;

/** Превышен лимит запросов (rate-limit). */
public class TooManyRequestsException extends RuntimeException {
    public TooManyRequestsException(String message) {
        super(message);
    }
}
