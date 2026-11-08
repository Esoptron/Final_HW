package ru.esoptron.currency.service;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.esoptron.currency.dto.RatePage;
import ru.esoptron.currency.dto.RateResponse;
import ru.esoptron.currency.model.CurrencyCode;
import ru.esoptron.currency.repository.ExchangeRateRepository;
import java.time.LocalDate;
import java.util.*;

@Service
@Transactional(readOnly = true)
public class RateQueryService {
    public enum SortBy { CURRENCY, RATE, DATE, CHANGE, CHANGE_PERCENT }
    public enum Direction { ASC, DESC }
    private final ExchangeRateRepository repository;

    public RateQueryService(ExchangeRateRepository repository) { this.repository = repository; }

    public RatePage find(LocalDate date, CurrencyCode currency, SortBy sortBy, Direction direction, int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw new IllegalArgumentException("page >= 0, size от 1 до 100");
        LocalDate selected = date != null ? date : repository.findLatestDate().orElse(null);
        if (selected == null) return new RatePage(List.of(), page, size, 0, 0);
        // JPA-сущности читаем в потоке транзакции. В parallelStream передаём только неизменяемые DTO.
        var snapshot = repository.findByRequestedDate(selected).stream().map(RateResponse::from).toList();
        Comparator<RateResponse> comparator = switch (sortBy) {
            case CURRENCY -> Comparator.comparing(RateResponse::currency);
            case RATE -> Comparator.comparing(RateResponse::rate);
            case DATE -> Comparator.comparing(RateResponse::rateDate);
            case CHANGE -> Comparator.comparing(RateResponse::change);
            case CHANGE_PERCENT -> Comparator.comparing(RateResponse::changePercent);
        };
        if (direction == Direction.DESC) comparator = comparator.reversed();
        var filtered = snapshot.parallelStream()
                .filter(rate -> currency == null || rate.currency() == currency)
                .sorted(comparator.thenComparing(RateResponse::currency)).toList();
        long offset = (long) page * size;
        var content = offset >= filtered.size() ? List.<RateResponse>of()
                : filtered.subList((int) offset, (int) Math.min(offset + size, filtered.size()));
        return new RatePage(content, page, size, filtered.size(), (filtered.size() + size - 1) / size);
    }

    public RateResponse get(LocalDate date, CurrencyCode currency) {
        return repository.findByRequestedDateAndCurrencyCode(date, currency).map(RateResponse::from)
                .orElseThrow(() -> new NoSuchElementException("Курс " + currency + " за " + date
                        + " ещё не загружен. Выполните POST /api/collections с этой датой"));
    }
}
