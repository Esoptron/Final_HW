package ru.esoptron.currency.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.esoptron.currency.model.ExchangeRate;
import ru.esoptron.currency.repository.ExchangeRateRepository;
import java.util.List;

@Service
public class RateStorage {
    private final ExchangeRateRepository repository;

    public RateStorage(ExchangeRateRepository repository) { this.repository = repository; }

    @Transactional
    public void saveBatch(List<ExchangeRate> rates) {
        for (ExchangeRate rate : rates) {
            var existing = repository.findByRequestedDateAndCurrencyCode(rate.getRequestedDate(), rate.getCurrencyCode());
            if (existing.isPresent()) existing.get().refreshFrom(rate);
            else repository.save(rate);
        }
        repository.flush();
    }
}
