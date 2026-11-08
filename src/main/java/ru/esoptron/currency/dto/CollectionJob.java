package ru.esoptron.currency.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record CollectionJob(UUID id, LocalDate date, Status status, Instant submittedAt,
                            Instant finishedAt, int savedRates, String error) {
    public enum Status { QUEUED, RUNNING, COMPLETED, FAILED }
    public boolean terminal() { return status == Status.COMPLETED || status == Status.FAILED; }
    public CollectionJob running() { return new CollectionJob(id, date, Status.RUNNING, submittedAt, null, 0, null); }
    public CollectionJob finished(Status state, Instant time, int count, String message) {
        return new CollectionJob(id, date, state, submittedAt, time, count, message);
    }
}
