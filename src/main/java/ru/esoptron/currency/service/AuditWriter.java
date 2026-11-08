package ru.esoptron.currency.service;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class AuditWriter {
    private static final Logger log = LoggerFactory.getLogger(AuditWriter.class);
    private final BlockingQueue<String> events = new ArrayBlockingQueue<>(256);
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final Thread writer;

    public AuditWriter(@Value("${collector.audit-file:./logs/collections.log}") String file) {
        writer = new Thread(new WriterTask(Path.of(file)), "currency-audit");
        writer.setDaemon(true);
        writer.start();
    }

    public void record(String event) {
        if (!events.offer(Instant.now() + " " + event.replace('\n', ' ').replace('\r', ' '))) {
            log.warn("Очередь журнала заполнена: {}", event);
        }
    }

    private class WriterTask implements Runnable {
        private final Path file;
        private WriterTask(Path file) { this.file = file; }
        @Override public void run() {
            try {
                Path parent = file.toAbsolutePath().getParent();
                Files.createDirectories(parent);
                try (BufferedWriter output = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    while (running.get() || !events.isEmpty()) {
                        String event;
                        try {
                            event = events.poll(200, TimeUnit.MILLISECONDS);
                        } catch (InterruptedException ex) {
                            continue; // При остановке дописываем оставшиеся события.
                        }
                        if (event != null) {
                            output.write(event);
                            output.newLine();
                            output.flush();
                        }
                    }
                }
            } catch (IOException ex) {
                log.error("Не удалось записать журнал {}", file, ex);
            }
        }
    }

    @PreDestroy
    public void close() {
        running.set(false);
        writer.interrupt();
        try { writer.join(3000); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
    }
}
