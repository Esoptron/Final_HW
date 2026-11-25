package ru.esoptron.currency.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.esoptron.currency.model.CurrencyCode;
import ru.esoptron.currency.model.ExchangeRate;
import ru.esoptron.currency.repository.ExchangeRateRepository;
import java.util.List;
import java.util.EnumMap;

@Service
public class RateStorage {
    private final ExchangeRateRepository repository;

    public RateStorage(ExchangeRateRepository repository) { this.repository = repository; }

    @Transactional
    public void saveBatch(List<ExchangeRate> rates) {
        if (rates.isEmpty()) return;
        var date = rates.get(0).getRequestedDate();
        var existing = new EnumMap<CurrencyCode, ExchangeRate>(CurrencyCode.class);
        for (ExchangeRate rate : repository.findByRequestedDate(date)) {
            existing.put(rate.getCurrencyCode(), rate);
        }
        for (ExchangeRate rate : rates) {
            if (!date.equals(rate.getRequestedDate())) {
                throw new IllegalArgumentException("Пакет курсов должен относиться к одной дате");
            }
            var saved = existing.get(rate.getCurrencyCode());
            if (saved == null) existing.put(rate.getCurrencyCode(), repository.save(rate));
            else saved.refreshFrom(rate);
        }
        repository.flush();
    }
}
