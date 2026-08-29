package com.velo.finance;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum CategoryKind {
    INCOME("income"),
    EXPENSE("expense");

    private final String value;

    CategoryKind(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static CategoryKind fromValue(String value) {
        for (CategoryKind kind : values()) {
            if (kind.value.equals(value)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Неизвестный тип статьи: " + value);
    }
}
