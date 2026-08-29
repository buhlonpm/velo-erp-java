package com.velo.rental;

public enum RentalStatus {
    /** Черновик: создана, активы в резерве, ещё не выдана. */
    DRAFT,
    ACTIVE,
    COMPLETED,
    /** Завершена досрочным возвратом (до конца оплаченного периода). */
    COMPLETED_EARLY,
    CANCELLED
}
