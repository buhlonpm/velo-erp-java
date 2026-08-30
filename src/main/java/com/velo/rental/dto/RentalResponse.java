package com.velo.rental.dto;

import com.velo.rental.Rental;
import com.velo.rental.RentalAmounts;
import com.velo.rental.RentalExtension;
import com.velo.rental.RentalItem;
import com.velo.rental.RentalKind;
import com.velo.rental.RentalSchedule;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RentalResponse(
        UUID id,
        UUID customerId,
        String customerName,
        String kind,
        /** active / overdue / completed / cancelled — вычисляемый статус. */
        String status,
        Instant startAt,
        Instant plannedEndAt,
        int deposit,
        Integer buyoutPrice,
        /** Срок выкупа в неделях (rent_to_own). */
        Integer termWeeks,
        String comment,
        /** Сумма аренды: черновик/active — начисленное (предоплаченный период + просрочка по факту,
         *  rent_to_own — цена выкупа); завершённая — зафиксирована как оплачено − возвращено. */
        int amount,
        /** Оплачено по аренде: только приходы (операции оплаты с rental_id). */
        int paidAmount,
        /** Возвращено клиенту: расходные операции с rental_id (блок «Возвраты»). */
        int refundedAmount,
        List<ItemResponse> items,
        Instant createdAt,
        /** Продления аренды в порядке создания. */
        List<ExtensionResponse> extensions,
        /** График платежей (rent_to_own) с вычисленным погашением; у rent — пустой список. */
        List<ScheduleItemResponse> schedule,
        /** Дата ближайшего непогашенного платежа (rent_to_own); null — всё оплачено или rent. */
        Instant nextPaymentDue
) {
    public record ItemResponse(
            UUID id,
            UUID assetId,
            String assetType,
            String assetName,
            String inventoryNumber,
            int rate,
            String tariffUnit,
            UUID parentItemId,
            Instant returnedAt
    ) {
        static ItemResponse from(RentalItem item) {
            return new ItemResponse(
                    item.getId(),
                    item.getAsset().getId(),
                    item.getAsset().getType().getValue(),
                    item.getAsset().getName(),
                    item.getAsset().getInventoryNumber(),
                    item.getRate(),
                    item.getTariffUnit().getValue(),
                    item.getParentItem() != null ? item.getParentItem().getId() : null,
                    item.getReturnedAt());
        }
    }

    public record ExtensionResponse(
            UUID id,
            int duration,
            String durationUnit,
            Instant fromEndAt,
            Instant toEndAt,
            Instant createdAt,
            String createdByName
    ) {
        static ExtensionResponse from(RentalExtension extension) {
            return new ExtensionResponse(
                    extension.getId(),
                    extension.getDuration(),
                    extension.getDurationUnit().getValue(),
                    extension.getFromEndAt(),
                    extension.getToEndAt(),
                    extension.getCreatedAt(),
                    extension.getCreatedBy() != null ? extension.getCreatedBy().getFullName() : null);
        }
    }

    /** Строка графика платежей: paidPart — сколько погашено (FIFO из суммы приходов). */
    public record ScheduleItemResponse(
            int seq,
            Instant dueDate,
            int amount,
            int paidPart,
            /** paid / partial / next / pending / overdue */
            String status
    ) {
    }

    public static RentalResponse from(Rental rental, Instant now, int paidAmount, int refundedAmount,
                                      List<RentalExtension> extensions) {
        int amount = RentalAmounts.total(rental, now, paidAmount, refundedAmount);
        boolean buyout = rental.getKind() == RentalKind.RENT_TO_OWN;
        List<ScheduleItemResponse> schedule = buyout
                ? RentalSchedule.states(rental.getScheduleItems(), now).stream()
                        .map(state -> new ScheduleItemResponse(
                                state.item().getSeq(),
                                state.item().getDueDate(),
                                state.item().getAmount(),
                                state.item().getCoveredAmount(),
                                state.status().name().toLowerCase()))
                        .toList()
                : List.of();
        Instant nextPaymentDue = buyout && !RentalAmounts.isFinished(rental)
                ? RentalSchedule.nextPaymentDue(rental.getScheduleItems())
                : null;
        return new RentalResponse(
                rental.getId(),
                rental.getCustomer().getId(),
                rental.getCustomer().getFullName(),
                rental.getKind().getValue(),
                rental.displayStatus(now),
                rental.getStartAt(),
                rental.getPlannedEndAt(),
                rental.getDeposit(),
                rental.getBuyoutPrice(),
                rental.getTermWeeks(),
                rental.getComment(),
                amount,
                paidAmount,
                refundedAmount,
                rental.getItems().stream().map(ItemResponse::from).toList(),
                rental.getCreatedAt(),
                extensions.stream().map(ExtensionResponse::from).toList(),
                schedule,
                nextPaymentDue);
    }
}
