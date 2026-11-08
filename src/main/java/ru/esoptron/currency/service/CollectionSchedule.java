package ru.esoptron.currency.service;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.concurrent.*;

@Component
@ConditionalOnProperty(name = "collector.enabled", havingValue = "true", matchIfMissing = true)
public class CollectionSchedule {
    private static final Logger log = LoggerFactory.getLogger(CollectionSchedule.class);
    private final ScheduledExecutorService scheduler;
    private final CollectionService service;
    private final Clock clock;
    private final Duration interval;
    private ScheduledFuture<?> future;

    public CollectionSchedule(ScheduledExecutorService collectionScheduler, CollectionService service, Clock clock,
                              @Value("${collector.interval:PT6H}") Duration interval) {
        if (interval.isNegative() || interval.isZero() || interval.toMillis() < 1000) {
            throw new IllegalArgumentException("Интервал сбора должен быть не меньше одной секунды");
        }
        this.scheduler = collectionScheduler;
        this.service = service;
        this.clock = clock;
        this.interval = interval;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        future = scheduler.scheduleWithFixedDelay(this::submitToday, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void submitToday() {
        try { service.submit(LocalDate.now(clock)); }
        catch (RuntimeException ex) { log.warn("Не удалось запланировать сбор: {}", ex.getMessage()); }
    }

    @PreDestroy
    public void close() { if (future != null) future.cancel(false); }
}
