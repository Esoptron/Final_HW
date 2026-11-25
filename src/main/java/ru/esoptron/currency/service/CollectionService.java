package ru.esoptron.currency.service;

import io.micrometer.observation.Observation;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import ru.esoptron.currency.client.CbrClient;
import ru.esoptron.currency.dto.CollectionJob;
import ru.esoptron.currency.model.CurrencyCode;
import ru.esoptron.currency.model.ExchangeRate;
import ru.esoptron.currency.observability.CollectionTelemetry;

import java.time.Clock;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

@Service
public class CollectionService {
    private static final Logger log = LoggerFactory.getLogger(CollectionService.class);
    private final ExecutorService executor;
    private final CbrClient client;
    private final RateStorage storage;
    private final AuditWriter audit;
    private final Clock clock;
    private final CollectionTelemetry telemetry;
    private final ConcurrentMap<UUID, CollectionJob> jobs = new ConcurrentHashMap<>();
    private final Map<LocalDate, UUID> inFlight = new HashMap<>(); // Доступ под монитором this.
    private final ReentrantLock writeLock = new ReentrantLock();

    public CollectionService(ExecutorService collectionExecutor, CbrClient client, RateStorage storage,
                             AuditWriter audit, Clock clock) {
        this(collectionExecutor, client, storage, audit, clock, CollectionTelemetry.NOOP);
    }

    @Autowired
    public CollectionService(ExecutorService collectionExecutor, CbrClient client, RateStorage storage,
                             AuditWriter audit, Clock clock, CollectionTelemetry telemetry) {
        this.executor = collectionExecutor;
        this.client = client;
        this.storage = storage;
        this.audit = audit;
        this.clock = clock;
        this.telemetry = telemetry;
    }

    public synchronized CollectionJob submit(LocalDate date) {
        if (date.isBefore(LocalDate.of(2000, 1, 2)) || date.isAfter(LocalDate.now(clock))) {
            throw new IllegalArgumentException("Дата должна быть между 2000-01-02 и сегодняшним днём");
        }
        UUID active = inFlight.get(date);
        if (active != null) return jobs.get(active);
        if (jobs.size() >= 200) {
            jobs.values().stream().filter(CollectionJob::terminal)
                    .min(Comparator.comparing(CollectionJob::submittedAt)).ifPresent(job -> jobs.remove(job.id()));
        }
        UUID id = UUID.randomUUID();
        CollectionJob job = new CollectionJob(id, date, CollectionJob.Status.QUEUED, clock.instant(), null, 0, null);
        jobs.put(id, job);
        inFlight.put(date, id);
        try {
            Observation parent = telemetry.current();
            long submittedNanos = System.nanoTime();
            executor.execute(() -> collect(job, parent, submittedNanos));
        } catch (RejectedExecutionException ex) {
            jobs.remove(id);
            inFlight.remove(date);
            throw ex;
        }
        return job;
    }

    public CollectionJob get(UUID id) {
        CollectionJob job = jobs.get(id);
        if (job == null) throw new NoSuchElementException("Задача не найдена или уже удалена из истории");
        return job;
    }

    public Optional<CollectionJob> latest() {
        return jobs.values().stream().max(Comparator.comparing(CollectionJob::submittedAt));
    }

    private void collect(CollectionJob job, Observation parent, long submittedNanos) {
        jobs.put(job.id(), job.running());
        audit.record("START id=" + job.id() + " date=" + job.date() + " thread=" + Thread.currentThread().getName());
        Observation collection = telemetry.startCollection(parent, submittedNanos);
        try (var scope = collection.openScope()) {
            var day = client.fetchDay(job.date());
            List<ExchangeRate> rates = telemetry.stage("rates.prepare", () -> prepare(job, day));
            // Блокировка охватывает вызов прокси: транзакция коммитится до снятия lock.
            writeLock.lockInterruptibly();
            try {
                telemetry.stage("rates.store", () -> {
                    storage.saveBatch(rates);
                    telemetry.saved(rates.size());
                    return null;
                });
            }
            finally { writeLock.unlock(); }
            collection.lowCardinalityKeyValue("outcome", "success");
            finish(job.finished(CollectionJob.Status.COMPLETED, clock.instant(), rates.size(), null));
        } catch (Exception ex) {
            collection.lowCardinalityKeyValue("outcome", "error").error(ex);
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            log.warn("Ошибка сбора за {}: {}", job.date(), ex.getMessage());
            log.debug("Причина ошибки сбора за " + job.date(), ex);
            finish(job.finished(CollectionJob.Status.FAILED, clock.instant(), 0,
                    ex instanceof ru.esoptron.currency.client.SourceException ? ex.getMessage() : "Не удалось сохранить курсы"));
        } finally {
            collection.stop();
        }
    }

    private List<ExchangeRate> prepare(CollectionJob job, CbrClient.DailyRates day) {
        var current = day.current();
        var previous = day.previous();
        var collectedAt = clock.instant();
        return Arrays.stream(CurrencyCode.values()).map(code -> {
            var quote = current.quotes().get(code);
            return new ExchangeRate(job.date(), current.rateDate(), previous.rateDate(), code,
                    quote.name(), quote.nominal(), quote.rate(), previous.quotes().get(code).rate(), collectedAt,
                    current.sourceUrl());
        }).toList();
    }

    private synchronized void finish(CollectionJob job) {
        jobs.put(job.id(), job);
        inFlight.remove(job.date(), job.id());
        audit.record(job.status() + " id=" + job.id() + " date=" + job.date() + " saved=" + job.savedRates()
                + (job.error() == null ? "" : " error=" + job.error()));
    }

    @PreDestroy
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(25, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException ex) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
