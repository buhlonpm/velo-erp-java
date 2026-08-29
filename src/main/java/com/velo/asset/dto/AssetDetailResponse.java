package com.velo.asset.dto;

import com.velo.finance.dto.TransactionResponse;
import com.velo.rental.dto.RentalResponse;

import java.util.List;

/** Карточка актива: паспорт + журналы + финансовые итоги. */
public record AssetDetailResponse(
        AssetResponse asset,
        /** Смонтированная АКБ (только для велосипеда). */
        List<AssetResponse> mountedBatteries,
        /** Смонтированный зарядник (только для велосипеда). */
        List<AssetResponse> mountedChargers,
        List<MileageLogEntry> mileageLog,
        /** Журнал циклов перезарядки (только для АКБ). */
        List<ChargeCycleLogEntry> chargeCycleLog,
        List<TransactionResponse> transactions,
        List<RentalResponse> rentals,
        List<AssetEventResponse> events,
        Totals totals
) {
    public record Totals(
            /** Цена покупки самого актива (0, если не указана). */
            int purchasePrice,
            /** Расходные операции с привязкой к активу (ремонты и пр.). */
            int expensesTotal,
            /** Приходные операции с привязкой к активу (выплаты за повреждения и пр.). */
            int incomeTotal,
            /** Начислено по позициям аренд с этим активом (по текущий момент). */
            int rentalAccruedTotal,
            /** Смонтированная АКБ: покупка + её расходные операции (ремонты). */
            int batteryTotal,
            /** Смонтированный зарядник: покупка + его расходные операции. */
            int chargerTotal
    ) {
        /** Сколько вложено: покупка + расходы + АКБ + зарядник. */
        public int invested() {
            return purchasePrice + expensesTotal + batteryTotal + chargerTotal;
        }

        /** Сколько принёс: аренды + приходные операции. */
        public int earned() {
            return rentalAccruedTotal + incomeTotal;
        }

        /** Окупаемость: принёс − вложено. */
        public int payback() {
            return earned() - invested();
        }
    }
}
