package com.velo.bikemodel;

import com.velo.bikemodel.dto.BikeModelResponse;
import com.velo.bikemodel.dto.CreateBikeModelRequest;
import com.velo.bikemodel.dto.UpdateBikeModelRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/bike-models")
@RequiredArgsConstructor
public class BikeModelController {

    private final BikeModelService bikeModelService;

    /** Читают все сотрудники — модель нужна при заведении велосипеда. */
    @GetMapping
    public List<BikeModelResponse> findAll() {
        return bikeModelService.findAll();
    }

    /** Справочник ведёт только админ (раздел «Настройки»). */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BikeModelResponse> create(@Valid @RequestBody CreateBikeModelRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(bikeModelService.create(request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        bikeModelService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public BikeModelResponse update(@PathVariable UUID id,
                                    @Valid @RequestBody UpdateBikeModelRequest request) {
        return bikeModelService.update(id, request);
    }
}
