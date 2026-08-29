package com.velo.asset;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/** Причина выбытия актива. */
public enum WriteOffReason {
    BROKEN("broken"),
    STOLEN("stolen"),
    LOST("lost"),
    SOLD("sold"),
    OTHER("other");

    private final String value;

    WriteOffReason(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static WriteOffReason fromValue(String value) {
        for (WriteOffReason reason : values()) {
            if (reason.value.equals(value)) {
                return reason;
            }
        }
        throw new IllegalArgumentException("Неизвестная причина выбытия: " + value);
    }
}
