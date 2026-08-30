package com.velo.rental;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * Что делать с переплатой по графику «под выкуп» (выбирается при приёме оплаты).
 * Без стратегии переплата просто гасит ближайшие платежи (FIFO) — клиент может их пропустить.
 */
public enum OverpaymentStrategy {
    /** Срок короче: недельный платёж тот же, лишние платежи снимаются с конца графика. */
    SHORTEN_TERM("shorten_term"),
    /** Платёж меньше: остаток к оплате размазывается равномерно по оставшимся платежам. */
    REDUCE_NEXT("reduce_next");

    private final String value;

    OverpaymentStrategy(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static OverpaymentStrategy fromValue(String value) {
        for (OverpaymentStrategy strategy : values()) {
            if (strategy.value.equals(value)) {
                return strategy;
            }
        }
        throw new IllegalArgumentException("Неизвестная стратегия переплаты: " + value);
    }
}
