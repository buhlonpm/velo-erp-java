package com.velo.tariff;

import com.velo.tariff.dto.CreateTariffRequest;
import com.velo.tariff.dto.TariffResponse;
import com.velo.tariff.dto.UpdateTariffRequest;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/tariffs")
@RequiredArgsConstructor
public class TariffController {

    private final TariffService tariffService;

    /** Тарифы модели. Читают все сотрудники — нужны в конструкторе аренды. */
    @GetMapping
    public List<TariffResponse> findAll(@RequestParam UUID modelId) {
        return tariffService.findAll(modelId);
    }

    /** Справочник ведёт только админ. */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<TariffResponse> create(@Valid @RequestBody CreateTariffRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(tariffService.create(request));
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public TariffResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateTariffRequest request) {
        return tariffService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        tariffService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
