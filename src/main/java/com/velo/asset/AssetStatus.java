package com.velo.asset;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum AssetStatus {
    AVAILABLE("available"),
    /** АКБ/зарядник смонтированы на велосипеде (не «доступны» и не «в аренде» сами по себе). */
    MOUNTED("mounted"),
    /** Зарезервирован под аренду-черновик (выдачи ещё не было). */
    RESERVED("reserved"),
    RENTED("rented"),
    MAINTENANCE("maintenance"),
    SOLD("sold"),
    /** Выкуплен клиентом по договору rent_to_own (деньги уже пришли платежами, операции нет). */
    BOUGHT_OUT("bought_out"),
    DECOMMISSIONED("decommissioned");

    private final String value;

    AssetStatus(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static AssetStatus fromValue(String value) {
        for (AssetStatus status : values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Неизвестный статус актива: " + value);
    }
}
