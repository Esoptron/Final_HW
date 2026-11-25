package ru.esoptron.currency.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Component
public class CollectionTelemetry {
    private static final Logger log = LoggerFactory.getLogger(CollectionTelemetry.class);
    public static final CollectionTelemetry NOOP =
            new CollectionTelemetry(ObservationRegistry.NOOP, new SimpleMeterRegistry(), false);
    private final ObservationRegistry observations;
    private final Counter saved;
    private final boolean logStages;

    public CollectionTelemetry(ObservationRegistry observations, MeterRegistry meters,
                               @Value("${observability.log-stages:false}") boolean logStages) {
        this.observations = observations;
        this.saved = Counter.builder("currency.records.saved")
                .description("Rows successfully written in committed collection transactions")
                .register(meters);
        this.logStages = logStages;
    }

    public Observation current() {
        return observations.getCurrentObservation();
    }

    public Observation startCollection(Observation parent, long submittedNanos) {
        var observation = Observation.createNotStarted("currency.collection", observations);
        if (parent != null) observation.parentObservation(parent);
        if (logStages) {
            observation.highCardinalityKeyValue("queue.ms",
                    Long.toString(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - submittedNanos)));
        }
        return observation.start();
    }

    public <T> T parse(String format, Supplier<T> work) {
        var observation = Observation.createNotStarted("currency.parse.duration", observations)
                .lowCardinalityKeyValue("format", format);
        return measure(observation, "parse." + format, work);
    }

    public <T> T stage(String name, Supplier<T> work) {
        return measure(Observation.createNotStarted("currency." + name, observations), name, work);
    }

    public void saved(int count) {
        saved.increment(count);
    }

    private <T> T measure(Observation observation, String stage, Supplier<T> work) {
        long start = logStages ? System.nanoTime() : 0;
        String outcome = "success";
        observation.start();
        try (var scope = observation.openScope()) {
            try {
                return work.get();
            } catch (RuntimeException ex) {
                outcome = "error";
                observation.error(ex);
                throw ex;
            } finally {
                observation.lowCardinalityKeyValue("outcome", outcome);
                if (logStages) {
                    log.info("stage={} outcome={} duration_us={}", stage, outcome,
                            TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - start));
                }
            }
        } finally {
            observation.stop();
        }
    }
}
