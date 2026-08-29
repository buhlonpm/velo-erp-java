package com.velo.rental;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum RentalKind {
    RENT("rent"),
    RENT_TO_OWN("rent_to_own");

    private final String value;

    RentalKind(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static RentalKind fromValue(String value) {
        for (RentalKind kind : values()) {
            if (kind.value.equals(value)) {
                return kind;
            }
        }
        throw new IllegalArgumentException("Неизвестный тип аренды: " + value);
    }
}
