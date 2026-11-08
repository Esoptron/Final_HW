package ru.esoptron.currency.client;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Component
public class CbrClient {
    public static final String SOURCE = "https://www.cbr.ru/scripts/XML_daily.asp";
    private final RestTemplate restTemplate;
    private final CbrXmlParser parser;

    public CbrClient(RestTemplate restTemplate, CbrXmlParser parser) {
        this.restTemplate = restTemplate;
        this.parser = parser;
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
