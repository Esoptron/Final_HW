package ru.esoptron.currency.client;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Component
public class CbrClient {
    private static final Logger log = LoggerFactory.getLogger(CbrClient.class);
    public static final String SOURCE = "https://www.cbr.ru/scripts/XML_daily.asp";
    private final RestTemplate restTemplate;
    private final CbrXmlParser parser;
    private final BackupCurrencyClient backup;
    private final boolean fallbackEnabled;

    public CbrClient(RestTemplate restTemplate, CbrXmlParser parser) {
        this(restTemplate, parser, null, false);
    }

    @Autowired
    public CbrClient(RestTemplate restTemplate, CbrXmlParser parser, BackupCurrencyClient backup,
                     @Value("${rates.fallback-enabled:true}") boolean fallbackEnabled) {
        this.restTemplate = restTemplate;
        this.parser = parser;
        this.backup = backup;
        this.fallbackEnabled = fallbackEnabled;
    }

    public record DailyRates(RateSnapshot current, RateSnapshot previous) { }

    public DailyRates fetchDay(LocalDate date) {
        try {
            return new DailyRates(fetch(date), fetch(date.minusDays(1)));
        } catch (SourceException ex) {
            if (!fallbackEnabled || backup == null) throw ex;
            log.warn("ЦБ РФ не ответил за {}. Используется резервный Currency API", date);
            // Оба дня загружаем из одного провайдера, чтобы не смешивать разные курсы в расчёте изменения.
            return new DailyRates(backup.fetch(date), backup.fetch(date.minusDays(1)));
        }
    }

    public RateSnapshot fetch(LocalDate date) {
        URI uri = UriComponentsBuilder.fromUriString(SOURCE)
                .queryParam("date_req", date.format(DateTimeFormatter.ofPattern("dd/MM/uuuu")))
                .build().toUri();
        try {
            RateSnapshot snapshot = parser.parse(restTemplate.getForObject(uri, byte[].class));
            if (snapshot.rateDate().isAfter(date)) {
                throw new SourceException("Источник вернул курс из будущего относительно запрошенной даты");
            }
            return snapshot;
        } catch (RestClientException ex) {
            throw new SourceException("ЦБ РФ недоступен: не удалось загрузить курсы за " + date, ex);
        }
    }
}
