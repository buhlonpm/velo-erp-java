package com.velo.tariff;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum TariffUnit {
    HOUR("hour", 3_600),
    DAY("day", 86_400),
    WEEK("week", 604_800),
    /** Месяц считаем как 30 суток. */
    MONTH("month", 2_592_000);

    private final String value;
    private final long seconds;

    TariffUnit(String value, long seconds) {
        this.value = value;
        this.seconds = seconds;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    public long getSeconds() {
        return seconds;
    }

    @JsonCreator
    public static TariffUnit fromValue(String value) {
        for (TariffUnit unit : values()) {
            if (unit.value.equals(value)) {
                return unit;
            }
        }
        throw new IllegalArgumentException("Неизвестная единица тарифа: " + value);
    }
}
