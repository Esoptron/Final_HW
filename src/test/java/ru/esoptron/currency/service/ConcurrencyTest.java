package ru.esoptron.currency.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.esoptron.currency.XmlFixtures;
import ru.esoptron.currency.client.CbrClient;
import ru.esoptron.currency.client.CbrXmlParser;
import ru.esoptron.currency.client.RateSnapshot;
import ru.esoptron.currency.dto.CollectionJob;
import ru.esoptron.currency.model.ExchangeRate;
import java.nio.file.Path;
import java.time.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

class ConcurrencyTest {
    @TempDir Path temp;
    private final Clock clock = Clock.fixed(Instant.parse("2024-04-10T10:00:00Z"), ZoneOffset.UTC);

    @Test void runsDifferentDatesInParallelAndRejectsOverflow() throws Exception {
        CountDownLatch active = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger saves = new AtomicInteger();
        CbrClient client = new CbrClient(null, null) {
            @Override public RateSnapshot fetch(LocalDate date) {
                active.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timed out");
                } catch (InterruptedException ex) { throw new IllegalStateException(ex); }
                return new CbrXmlParser().parse(XmlFixtures.rates(date, "92,0000"));
            }
        };
        RateStorage storage = new RateStorage(null) {
            @Override public void saveBatch(List<ExchangeRate> rates) { saves.incrementAndGet(); }
        };
        ExecutorService executor = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(1));
        AuditWriter audit = new AuditWriter(temp.resolve("audit.log").toString());
        CollectionService service = new CollectionService(executor, client, storage, audit, clock);
        try {
            var one = service.submit(LocalDate.of(2024, 4, 2));
            var two = service.submit(LocalDate.of(2024, 4, 3));
            assertThat(active.await(5, TimeUnit.SECONDS)).isTrue();
            var three = service.submit(LocalDate.of(2024, 4, 4));
            assertThatThrownBy(() -> service.submit(LocalDate.of(2024, 4, 5))).isInstanceOf(RejectedExecutionException.class);
            release.countDown();
            await().atMost(Duration.ofSeconds(5)).until(() -> service.get(one.id()).terminal()
                    && service.get(two.id()).terminal() && service.get(three.id()).terminal());
            assertThat(saves).hasValue(3);
            assertThat(service.get(one.id()).status()).isEqualTo(CollectionJob.Status.COMPLETED);
        } finally {
            release.countDown();
            service.close();
            audit.close();
        }
        assertThat(temp.resolve("audit.log")).content().contains("START", "COMPLETED");
        assertThat(executor.isTerminated()).isTrue();
    }

    @Test void schedulerSubmitsAtStartupAndRepeats() {
        AtomicInteger submissions = new AtomicInteger();
        CollectionService service = new CollectionService(null, null, null, null, clock) {
            @Override public synchronized CollectionJob submit(LocalDate date) {
                assertThat(date).isEqualTo(LocalDate.of(2024, 4, 10));
                submissions.incrementAndGet();
                return null;
            }
        };
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        CollectionSchedule schedule = new CollectionSchedule(executor, service, clock, Duration.ofSeconds(1));
        try {
            schedule.start();
            await().atMost(Duration.ofSeconds(4)).until(() -> submissions.get() >= 2);
        } finally {
            schedule.close();
            executor.shutdownNow();
        }
    }
}
