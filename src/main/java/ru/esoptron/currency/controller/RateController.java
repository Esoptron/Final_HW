package ru.esoptron.currency.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.esoptron.currency.dto.*;
import ru.esoptron.currency.model.CurrencyCode;
import ru.esoptron.currency.service.*;
import java.net.URI;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
public class RateController {
    private final CollectionService collections;
    private final RateQueryService queries;
    private final Clock clock;

    public RateController(CollectionService collections, RateQueryService queries, Clock clock) {
        this.collections = collections;
        this.queries = queries;
        this.clock = clock;
    }

    @GetMapping("/")
    public ResponseEntity<Void> home() {
        return ResponseEntity.status(HttpStatus.FOUND).location(URI.create("/answer")).build();
    }

    @GetMapping("/api/currencies")
    public List<CurrencyCode> currencies() { return List.of(CurrencyCode.values()); }

    @PostMapping("/api/collections")
    public ResponseEntity<CollectionJob> collect(@RequestBody(required = false) CollectionRequest request) {
        if (request != null) request.validateSource();
        LocalDate date = request == null || request.date() == null ? LocalDate.now(clock) : request.date();
        CollectionJob job = collections.submit(date);
        return ResponseEntity.accepted().location(URI.create("/api/collections/" + job.id())).body(job);
    }

    @GetMapping("/api/collections/{id}")
    public CollectionJob job(@PathVariable UUID id) { return collections.get(id); }

    @GetMapping({"/answer", "/api/rates"})
    public RatePage rates(@RequestParam(required = false) LocalDate date,
                          @RequestParam(required = false) CurrencyCode currency,
                          @RequestParam(defaultValue = "CURRENCY") RateQueryService.SortBy sortBy,
                          @RequestParam(defaultValue = "ASC") RateQueryService.Direction direction,
                          @RequestParam(defaultValue = "0") int page,
                          @RequestParam(defaultValue = "20") int size) {
        return queries.find(date, currency, sortBy, direction, page, size);
    }

    @GetMapping("/api/rates/{currency}")
    public RateResponse rate(@PathVariable CurrencyCode currency, @RequestParam LocalDate date) {
        return queries.get(date, currency);
    }
}
