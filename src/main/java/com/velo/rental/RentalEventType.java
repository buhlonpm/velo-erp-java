package com.velo.rental;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum RentalEventType {
    CREATED("created"),
    PAYMENT("payment"),
    ISSUED("issued"),
    EXTENSION("extension"),
    /** Изменение условий выкупа: сумма, график платежей. */
    SCHEDULE("schedule"),
    ITEM_RETURN("item_return"),
    REFUND("refund"),
    COMPLETED("completed");

    private final String value;

    RentalEventType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static RentalEventType fromValue(String value) {
        for (RentalEventType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Неизвестный тип события аренды: " + value);
    }
}
