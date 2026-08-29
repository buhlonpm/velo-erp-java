package com.velo.asset;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum AssetType {
    BIKE("bike"),
    BATTERY("battery"),
    CHARGER("charger");

    private final String value;

    AssetType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static AssetType fromValue(String value) {
        for (AssetType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Неизвестный тип актива: " + value);
    }
}
