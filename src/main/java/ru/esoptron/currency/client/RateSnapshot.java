package ru.esoptron.currency.client;

import ru.esoptron.currency.model.CurrencyCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

public record RateSnapshot(LocalDate rateDate, Map<CurrencyCode, Quote> quotes) {
    public RateSnapshot {
        quotes = Map.copyOf(quotes);
    }

    public record Quote(String name, int nominal, BigDecimal rate) { }
}
