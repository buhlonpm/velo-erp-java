package com.velo.rental;

import com.velo.rental.dto.CompleteRentalRequest;
import com.velo.rental.dto.CreateRentalRequest;
import com.velo.rental.dto.ExtendRentalRequest;
import com.velo.rental.dto.ExtensionRequest;
import com.velo.rental.dto.IssueRentalRequest;
import com.velo.rental.dto.PaymentRequest;
import com.velo.rental.dto.RentalEventResponse;
import com.velo.rental.dto.RentalResponse;
import com.velo.rental.dto.ReturnItemRequest;
import com.velo.rental.dto.UpdateRentalRequest;
import com.velo.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/rentals")
@RequiredArgsConstructor
public class RentalController {

    private final RentalService rentalService;

    @GetMapping
    public List<RentalResponse> findAll(@RequestParam(required = false) String status) {
        return rentalService.findAll(status);
    }

    @GetMapping("/{id}")
    public RentalResponse findById(@PathVariable UUID id) {
        return rentalService.findById(id);
    }

    @GetMapping("/{id}/events")
    public List<RentalEventResponse> events(@PathVariable UUID id) {
        return rentalService.events(id);
    }

    @PostMapping
    public ResponseEntity<RentalResponse> create(@Valid @RequestBody CreateRentalRequest request,
                                                 @AuthenticationPrincipal User author) {
        return ResponseEntity.status(HttpStatus.CREATED).body(rentalService.create(request, author));
    }

    /** Правка суммы выкупа (только rent_to_own, черновик/активная) — график пересчитывается. */
    @PatchMapping("/{id}")
    public RentalResponse update(@PathVariable UUID id,
                                 @Valid @RequestBody UpdateRentalRequest request,
                                 @AuthenticationPrincipal User author) {
        return rentalService.updateBuyoutPrice(id, request, author);
    }

    @PostMapping("/{id}/payments")
    public ResponseEntity<RentalResponse> addPayment(@PathVariable UUID id,
                                                     @Valid @RequestBody PaymentRequest request,
                                                     @AuthenticationPrincipal User author) {
        return ResponseEntity.status(HttpStatus.CREATED).body(rentalService.addPayment(id, request, author));
    }

    @PostMapping("/{id}/issue")
    public RentalResponse issue(@PathVariable UUID id,
                                @RequestBody(required = false) IssueRentalRequest request,
                                @AuthenticationPrincipal User author) {
        return rentalService.issue(id, request, author);
    }

    @PostMapping("/{id}/complete")
    public RentalResponse complete(@PathVariable UUID id,
                                   @RequestBody(required = false) CompleteRentalRequest request,
                                   @AuthenticationPrincipal User author) {
        return rentalService.complete(id, request, author);
    }

    @PostMapping("/{id}/early-return")
    public RentalResponse earlyReturn(@PathVariable UUID id,
                                      @RequestBody(required = false) ReturnItemRequest request,
                                      @AuthenticationPrincipal User author) {
        return rentalService.earlyReturn(id, request, author);
    }

    @PostMapping("/{id}/extend")
    public RentalResponse extend(@PathVariable UUID id,
                                 @Valid @RequestBody ExtendRentalRequest request,
                                 @AuthenticationPrincipal User author) {
        return rentalService.extend(id, request, author);
    }

    @PatchMapping("/{id}/extensions/{extId}")
    public RentalResponse updateExtension(@PathVariable UUID id, @PathVariable UUID extId,
                                          @Valid @RequestBody ExtensionRequest request,
                                          @AuthenticationPrincipal User author) {
        return rentalService.updateExtension(id, extId, request, author);
    }

    @DeleteMapping("/{id}/extensions/{extId}")
    public RentalResponse deleteExtension(@PathVariable UUID id, @PathVariable UUID extId,
                                          @AuthenticationPrincipal User author) {
        return rentalService.deleteExtension(id, extId, author);
    }

    @PostMapping("/{id}/items/{itemId}/return")
    public RentalResponse returnItem(@PathVariable UUID id, @PathVariable UUID itemId,
                                     @RequestBody(required = false) ReturnItemRequest request,
                                     @AuthenticationPrincipal User author) {
        return rentalService.returnItem(id, itemId, request, author);
    }

    @PostMapping("/{id}/cancel")
    public RentalResponse cancel(@PathVariable UUID id, @AuthenticationPrincipal User author) {
        return rentalService.cancel(id, author);
    }

    /** Удаление аренды без следа (только финальные статусы) — только ADMIN. */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        rentalService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
