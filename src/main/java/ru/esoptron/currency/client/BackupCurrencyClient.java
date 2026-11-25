package ru.esoptron.currency.client;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.client.RestTemplate;
import ru.esoptron.currency.model.CurrencyCode;
import ru.esoptron.currency.observability.CollectionTelemetry;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.Locale;

@Component
public class BackupCurrencyClient {
    public static final String CDN = "https://cdn.jsdelivr.net/npm/@fawazahmed0/currency-api@";
    private static final String PATH = "/v1/currencies/rub.json";
    private final RestTemplate restTemplate;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final CollectionTelemetry telemetry;

    public BackupCurrencyClient(RestTemplate restTemplate, ObjectMapper mapper, Clock clock) {
        this(restTemplate, mapper, clock, CollectionTelemetry.NOOP);
    }

    @Autowired
    public BackupCurrencyClient(RestTemplate restTemplate, ObjectMapper mapper, Clock clock,
                                CollectionTelemetry telemetry) {
        this.restTemplate = restTemplate;
        this.mapper = mapper;
        this.clock = clock;
        this.telemetry = telemetry;
    }

    public RateSnapshot fetch(LocalDate date) {
        String version = date.equals(LocalDate.now(clock)) ? "latest" : date.toString();
        try {
            return fetchFrom(CDN + version + PATH, date);
        } catch (SourceException ex) {
            return fetchFrom("https://" + version + ".currency-api.pages.dev" + PATH, date);
        }
    }

    private RateSnapshot fetchFrom(String url, LocalDate requestedDate) {
        try {
            byte[] body = telemetry.stage("json.download", () -> restTemplate.getForObject(url, byte[].class));
            return telemetry.parse("json", () -> parse(body, url, requestedDate));
        } catch (Exception ex) {
            throw new SourceException("Резервный источник недоступен или не содержит курсов за " + requestedDate, ex);
        }
    }

    private RateSnapshot parse(byte[] body, String url, LocalDate requestedDate) {
        try {
            if (body == null || body.length == 0 || body.length > 1_000_000) {
                throw new IllegalArgumentException("Пустой или слишком большой ответ");
            }
            var json = mapper.reader().with(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).readTree(body);
            LocalDate rateDate = LocalDate.parse(json.path("date").asText());
            if (rateDate.isAfter(requestedDate)
                    || (!url.contains("latest") && !rateDate.equals(requestedDate))) {
                throw new IllegalArgumentException("Дата ответа не соответствует запросу");
            }
            var quotes = new EnumMap<CurrencyCode, RateSnapshot.Quote>(CurrencyCode.class);
            for (CurrencyCode code : CurrencyCode.values()) {
                var node = json.path("rub").path(code.name().toLowerCase(Locale.ROOT));
                if (!node.isNumber() || node.decimalValue().signum() <= 0) {
                    throw new IllegalArgumentException("Некорректный курс " + code);
                }
                // Источник возвращает валюту за 1 RUB; API приложения - RUB за 1 валюты.
                BigDecimal rate = BigDecimal.ONE.divide(node.decimalValue(), 8, RoundingMode.HALF_UP);
                String name = switch (code) {
                    case USD -> "Доллар США";
                    case EUR -> "Евро";
                    case CNY -> "Юань";
                    case GBP -> "Фунт стерлингов";
                    case JPY -> "Иена";
                };
                quotes.put(code, new RateSnapshot.Quote(name, 1, rate));
            }
            return new RateSnapshot(rateDate, quotes, url);
        } catch (java.io.IOException ex) {
            throw new IllegalArgumentException("Некорректный JSON с курсами", ex);
        }
    }
}
