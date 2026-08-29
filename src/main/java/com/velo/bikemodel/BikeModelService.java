package com.velo.bikemodel;

import com.velo.bikemodel.dto.BikeModelResponse;
import com.velo.bikemodel.dto.CreateBikeModelRequest;
import com.velo.bikemodel.dto.UpdateBikeModelRequest;
import com.velo.common.exception.ConflictException;
import com.velo.common.exception.NotFoundException;
import com.velo.tariff.TariffRepository;
import com.velo.tariff.dto.TariffResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BikeModelService {

    private final BikeModelRepository bikeModelRepository;
    private final TariffRepository tariffRepository;

    @Transactional(readOnly = true)
    public List<BikeModelResponse> findAll() {
        return bikeModelRepository.findAllByOrderByBrandAscModelAsc().stream()
                .map(model -> BikeModelResponse.from(model, tariffsOf(model.getId())))
                .toList();
    }

    @Transactional
    public BikeModelResponse create(CreateBikeModelRequest request) {
        if (bikeModelRepository.existsByBrandAndModel(request.brand().trim(), request.model().trim())) {
            throw new ConflictException("Такая модель уже есть в справочнике");
        }
        BikeModel model = new BikeModel();
        model.setBrand(request.brand().trim());
        model.setModel(request.model().trim());
        model.setSpecs(request.specs() != null ? request.specs().trim() : "");
        model.setMaxMileageKm(request.maxMileageKm());
        model.setResidualPercent(request.residualPercent());
        return BikeModelResponse.from(bikeModelRepository.save(model), List.of());
    }

    @Transactional
    public BikeModelResponse update(UUID id, UpdateBikeModelRequest request) {
        BikeModel model = bikeModelRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Модель не найдена"));
        String newBrand = request.brand() != null ? request.brand().trim() : model.getBrand();
        String newModel = request.model() != null ? request.model().trim() : model.getModel();
        if ((!newBrand.equals(model.getBrand()) || !newModel.equals(model.getModel()))
                && bikeModelRepository.existsByBrandAndModel(newBrand, newModel)) {
            throw new ConflictException("Такая модель уже есть в справочнике");
        }
        model.setBrand(newBrand);
        model.setModel(newModel);
        if (request.specs() != null) {
            model.setSpecs(request.specs().trim());
        }
        if (request.maxMileageKm() != null) {
            model.setMaxMileageKm(request.maxMileageKm());
        }
        if (request.residualPercent() != null) {
            model.setResidualPercent(request.residualPercent());
        }
        return BikeModelResponse.from(bikeModelRepository.save(model), tariffsOf(id));
    }

    @Transactional
    public void delete(UUID id) {
        BikeModel model = bikeModelRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Модель не найдена"));
        bikeModelRepository.delete(model);
    }

    private List<TariffResponse> tariffsOf(UUID modelId) {
        return tariffRepository.findAllByModelIdOrderByUnitAscPriceAsc(modelId).stream()
                .map(TariffResponse::from)
                .toList();
    }
}
