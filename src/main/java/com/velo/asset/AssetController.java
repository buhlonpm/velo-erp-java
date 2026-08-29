package com.velo.asset;

import com.velo.asset.dto.AssetDetailResponse;
import com.velo.asset.dto.AssetEventResponse;
import com.velo.asset.dto.AssetResponse;
import com.velo.asset.dto.ChargeCycleLogEntry;
import com.velo.asset.dto.CreateAssetRequest;
import com.velo.asset.dto.MileageLogEntry;
import com.velo.asset.dto.RecordChargeCyclesRequest;
import com.velo.asset.dto.RecordMileageRequest;
import com.velo.asset.dto.UpdateAssetRequest;
import com.velo.asset.dto.WriteOffAssetRequest;
import com.velo.user.User;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
@RequestMapping("/api/assets")
@RequiredArgsConstructor
public class AssetController {

    private final AssetService assetService;

    @GetMapping
    public List<AssetResponse> findAll(@RequestParam(required = false) AssetType type,
                                       @RequestParam(required = false) AssetStatus status) {
        return assetService.findAll(type, status);
    }

    @PostMapping
    public ResponseEntity<AssetResponse> create(@Valid @RequestBody CreateAssetRequest request,
                                                @AuthenticationPrincipal User author) {
        return ResponseEntity.status(HttpStatus.CREATED).body(assetService.create(request, author));
    }

    @PatchMapping("/{id}")
    public AssetResponse update(@PathVariable UUID id, @Valid @RequestBody UpdateAssetRequest request) {
        return assetService.update(id, request);
    }

    @PostMapping("/{id}/tracker/{trackerId}")
    public AssetResponse installTracker(@PathVariable UUID id, @PathVariable UUID trackerId) {
        return assetService.installTracker(id, trackerId);
    }

    @DeleteMapping("/{id}/tracker")
    public AssetResponse removeTracker(@PathVariable UUID id) {
        return assetService.removeTracker(id);
    }

    @PostMapping("/{assetId}/mount/{bikeId}")
    public AssetResponse mountOnBike(@PathVariable UUID assetId, @PathVariable UUID bikeId) {
        return assetService.mountOnBike(assetId, bikeId);
    }

    @DeleteMapping("/{assetId}/mount")
    public AssetResponse unmountFromBike(@PathVariable UUID assetId) {
        return assetService.unmountFromBike(assetId);
    }

    @PostMapping("/{id}/write-off")
    public AssetResponse writeOff(@PathVariable UUID id,
                                  @Valid @RequestBody WriteOffAssetRequest request,
                                  @AuthenticationPrincipal User author) {
        return assetService.writeOff(id, request, author);
    }

    @GetMapping("/{id}/events")
    public List<AssetEventResponse> events(@PathVariable UUID id) {
        return assetService.events(id);
    }

    @PostMapping("/{id}/mileage")
    public ResponseEntity<MileageLogEntry> recordMileage(@PathVariable UUID id,
                                                         @Valid @RequestBody RecordMileageRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(assetService.recordMileage(id, request));
    }

    @GetMapping("/{id}/mileage")
    public List<MileageLogEntry> mileageLog(@PathVariable UUID id) {
        return assetService.mileageLog(id);
    }

    @PostMapping("/{id}/charge-cycles")
    public ResponseEntity<ChargeCycleLogEntry> recordChargeCycles(@PathVariable UUID id,
                                                                  @Valid @RequestBody RecordChargeCyclesRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(assetService.recordChargeCycles(id, request));
    }

    @GetMapping("/{id}/charge-cycles")
    public List<ChargeCycleLogEntry> chargeCycleLog(@PathVariable UUID id) {
        return assetService.chargeCycleLog(id);
    }

    @GetMapping("/{id}/detail")
    public AssetDetailResponse detail(@PathVariable UUID id) {
        return assetService.detail(id);
    }
}
