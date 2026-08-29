package com.velo.rental.dto;

import com.velo.rental.Rental;
import com.velo.rental.RentalItem;

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
        String comment,
        /** Сумма по позициям на текущий момент (для rent_to_own — цена выкупа). */
        int amount,
        /** Сколько уже внесено по этой аренде (приходные операции с rental_id). */
        int paidAmount,
        List<ItemResponse> items,
        Instant createdAt
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

    public static RentalResponse from(Rental rental, Instant now, int paidAmount) {
        int amount = rental.getKind() == com.velo.rental.RentalKind.RENT_TO_OWN
                ? (rental.getBuyoutPrice() != null ? rental.getBuyoutPrice() : 0)
                : rental.getItems().stream().mapToInt(item -> item.amount(now)).sum();
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
                rental.getComment(),
                amount,
                paidAmount,
                rental.getItems().stream().map(ItemResponse::from).toList(),
                rental.getCreatedAt());
    }
}
