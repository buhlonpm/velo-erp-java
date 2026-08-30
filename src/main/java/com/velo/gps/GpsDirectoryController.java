package com.velo.gps;

import com.velo.gps.dto.CreateGpsTrackerRequest;
import com.velo.gps.dto.CreateSimCardRequest;
import com.velo.gps.dto.GpsTrackerResponse;
import com.velo.gps.dto.SimCardResponse;
import com.velo.gps.dto.UpdateGpsTrackerRequest;
import com.velo.gps.dto.UpdateSimCardRequest;
import com.velo.gps.dto.WriteOffSimCardRequest;
import com.velo.gps.dto.WriteOffTrackerRequest;
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

/** Справочники SIM-карт и GPS-трекеров: читают все, ведёт админ. */
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class GpsDirectoryController {

    private final GpsDirectoryService service;

    @GetMapping("/sim-cards")
    public List<SimCardResponse> findSimCards(@RequestParam(required = false) Boolean available) {
        return service.findSimCards(Boolean.TRUE.equals(available));
    }

    @PostMapping("/sim-cards")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SimCardResponse> createSimCard(@Valid @RequestBody CreateSimCardRequest request,
                                                         @AuthenticationPrincipal User author) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createSimCard(request, author));
    }

    /** Списание SIM-карты (продать нельзя). */
    @PostMapping("/sim-cards/{id}/write-off")
    @PreAuthorize("hasRole('ADMIN')")
    public SimCardResponse writeOffSimCard(@PathVariable UUID id,
                                           @Valid @RequestBody WriteOffSimCardRequest request) {
        return service.writeOffSimCard(id, request);
    }

    @PostMapping("/sim-cards/{id}/restore")
    @PreAuthorize("hasRole('ADMIN')")
    public SimCardResponse restoreSimCard(@PathVariable UUID id) {
        return service.restoreSimCard(id);
    }

    /** Удаление доступно всем: только неиспользованная запись (сервис даёт 409 иначе). */
    @DeleteMapping("/sim-cards/{id}")
    public ResponseEntity<Void> deleteSimCard(@PathVariable UUID id) {
        service.deleteSimCard(id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/sim-cards/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public SimCardResponse updateSimCard(@PathVariable UUID id,
                                         @Valid @RequestBody UpdateSimCardRequest request) {
        return service.updateSimCard(id, request);
    }

    @GetMapping("/gps-trackers")
    public List<GpsTrackerResponse> findTrackers(@RequestParam(required = false) Boolean available) {
        return service.findTrackers(Boolean.TRUE.equals(available));
    }

    @PostMapping("/gps-trackers")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<GpsTrackerResponse> createTracker(@Valid @RequestBody CreateGpsTrackerRequest request,
                                                            @AuthenticationPrincipal User author) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.createTracker(request, author));
    }

    /** Удаление доступно всем: только неиспользованный трекер (сервис даёт 409 иначе). */
    @DeleteMapping("/gps-trackers/{id}")
    public ResponseEntity<Void> deleteTracker(@PathVariable UUID id) {
        service.deleteTracker(id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/gps-trackers/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public GpsTrackerResponse updateTracker(@PathVariable UUID id,
                                            @Valid @RequestBody UpdateGpsTrackerRequest request) {
        return service.updateTracker(id, request);
    }

    /** Списание трекера (сломан/украден/утерян/прочее). Доступно всем сотрудникам. */
    @PostMapping("/gps-trackers/{id}/write-off")
    public GpsTrackerResponse writeOffTracker(@PathVariable UUID id,
                                              @RequestBody(required = false) WriteOffTrackerRequest request) {
        return service.writeOffTracker(id, request);
    }

    /** Вернуть трекер из списания (ошибочное списание). */
    @PostMapping("/gps-trackers/{id}/restore")
    @PreAuthorize("hasRole('ADMIN')")
    public GpsTrackerResponse restoreTracker(@PathVariable UUID id) {
        return service.restoreTracker(id);
    }
}
