package com.velo.asset;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum AssetEventType {
    PURCHASE("purchase"),
    MILEAGE("mileage"),
    CHARGE_CYCLES("charge_cycles"),
    MOUNT("mount"),
    UNMOUNT("unmount"),
    TRACKER_INSTALL("tracker_install"),
    TRACKER_REMOVE("tracker_remove"),
    WRITE_OFF("write_off"),
    /** Привязанная финансовая операция: приход/расход (создание, правка, удаление — по комментарию). */
    INCOME("income"),
    EXPENSE("expense");

    private final String value;

    AssetEventType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static AssetEventType fromValue(String value) {
        for (AssetEventType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Неизвестный тип события: " + value);
    }
}
