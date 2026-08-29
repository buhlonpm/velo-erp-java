package com.velo.asset;

import com.velo.finance.FinanceTransaction;
import com.velo.user.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/** Запись событий жизни актива. */
@Service
@RequiredArgsConstructor
public class AssetEventService {

    private final AssetEventRepository eventRepository;

    @Transactional(propagation = Propagation.REQUIRED)
    public void record(Asset asset, AssetEventType type, String comment, Integer amount,
                       FinanceTransaction transaction, User author) {
        AssetEvent event = new AssetEvent();
        event.setAsset(asset);
        event.setType(type);
        event.setDate(Instant.now());
        event.setComment(comment != null ? comment : "");
        event.setAmount(amount);
        event.setTransaction(transaction);
        event.setCreatedBy(author);
        eventRepository.save(event);
    }

    public void record(Asset asset, AssetEventType type, String comment) {
        record(asset, type, comment, null, null, null);
    }
}
