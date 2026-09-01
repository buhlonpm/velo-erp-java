package com.velo.tariff;

import com.velo.bikemodel.BikeModel;
import com.velo.bikemodel.BikeModelRepository;
import com.velo.common.exception.ConflictException;
import com.velo.common.exception.NotFoundException;
import com.velo.rental.RentalKind;
import com.velo.tariff.dto.CreateTariffRequest;
import com.velo.tariff.dto.TariffResponse;
import com.velo.tariff.dto.UpdateTariffRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TariffService {

    private final TariffRepository tariffRepository;
    private final BikeModelRepository bikeModelRepository;

    public List<TariffResponse> findAll(UUID modelId) {
        return tariffRepository.findAllByModelIdOrderByUnitAscPriceAsc(modelId).stream()
                .map(TariffResponse::from)
                .toList();
    }

    @Transactional
    public TariffResponse create(CreateTariffRequest request) {
        BikeModel model = bikeModelRepository.findById(request.modelId())
                .orElseThrow(() -> new NotFoundException("Модель не найдена"));
        RentalKind kind = request.kind() != null ? request.kind() : RentalKind.RENT;
        if (kind == RentalKind.RENT_TO_OWN) {
            // тариф под выкуп — единственный недельный тариф модели для договоров выкупа
            if (request.unit() != TariffUnit.WEEK) {
                throw new ConflictException("Тариф под выкуп — только недельный");
            }
            if (tariffRepository.existsByModelIdAndKind(model.getId(), RentalKind.RENT_TO_OWN)) {
                throw new ConflictException("У этой модели уже есть тариф под выкуп");
            }
        }
        String name = request.name().trim();
        if (tariffRepository.existsByModelIdAndNameAndUnitAndKind(model.getId(), name, request.unit(),
                kind)) {
            throw new ConflictException("У этой модели уже есть такой тариф");
        }
        Tariff tariff = new Tariff();
        tariff.setModel(model);
        tariff.setName(name);
        tariff.setUnit(request.unit());
        tariff.setPrice(request.price());
        tariff.setKind(kind);
        return TariffResponse.from(tariffRepository.save(tariff));
    }

    /** Изменение цены/названия влияет только на будущие аренды — в позициях снапшот. */
    @Transactional
    public TariffResponse update(UUID id, UpdateTariffRequest request) {
        Tariff tariff = tariffRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Тариф не найден"));
        if (request.name() != null && !request.name().isBlank()) {
            tariff.setName(request.name().trim());
        }
        if (request.price() != null) {
            tariff.setPrice(request.price());
        }
        return TariffResponse.from(tariffRepository.save(tariff));
    }

    @Transactional
    public void delete(UUID id) {
        Tariff tariff = tariffRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Тариф не найден"));
        tariffRepository.delete(tariff);
    }
}
