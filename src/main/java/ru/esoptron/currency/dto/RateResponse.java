package ru.esoptron.currency.dto;

import ru.esoptron.currency.model.CurrencyCode;
import ru.esoptron.currency.model.ExchangeRate;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;

public record RateResponse(LocalDate requestedDate, LocalDate rateDate, LocalDate previousRateDate,
                           CurrencyCode currency, String name, String baseCurrency, int nominal,
                           BigDecimal rate, BigDecimal previousRate, BigDecimal change,
                           BigDecimal changePercent, Instant collectedAt, String sourceUrl) {
    public static RateResponse from(ExchangeRate entity) {
        BigDecimal change = entity.getRate().subtract(entity.getPreviousRate());
        BigDecimal percent = change.multiply(BigDecimal.valueOf(100))
                .divide(entity.getPreviousRate(), 6, RoundingMode.HALF_UP);
        return new RateResponse(entity.getRequestedDate(), entity.getRateDate(), entity.getPreviousRateDate(),
                entity.getCurrencyCode(), entity.getName(), "RUB", entity.getNominal(), entity.getRate(),
                entity.getPreviousRate(), change, percent, entity.getCollectedAt(), entity.getSourceUrl());
    }
}
