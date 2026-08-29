package com.velo.rental;

import com.velo.rental.dto.CreateRentalRequest;
import com.velo.rental.dto.RentalResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
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

    @PostMapping
    public ResponseEntity<RentalResponse> create(@Valid @RequestBody CreateRentalRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(rentalService.create(request));
    }

    @PostMapping("/{id}/items/{itemId}/return")
    public RentalResponse returnItem(@PathVariable UUID id, @PathVariable UUID itemId) {
        return rentalService.returnItem(id, itemId);
    }

    @PostMapping("/{id}/cancel")
    public RentalResponse cancel(@PathVariable UUID id) {
        return rentalService.cancel(id);
    }
}
