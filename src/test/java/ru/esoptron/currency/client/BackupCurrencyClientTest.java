package ru.esoptron.currency.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import ru.esoptron.currency.XmlFixtures;
import ru.esoptron.currency.model.CurrencyCode;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class BackupCurrencyClientTest {
    private final RestTemplate rest = new RestTemplate();
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(rest).build();
    private final Clock clock = Clock.fixed(Instant.parse("2024-04-02T10:00:00Z"), ZoneOffset.UTC);
    private final BackupCurrencyClient backup = new BackupCurrencyClient(rest, new ObjectMapper(), clock);
    private static final String PATH = "/v1/currencies/rub.json";

    private String json(String date, String usd) {
        return "{\"date\":\"" + date + "\",\"rub\":{\"usd\":" + usd
                + ",\"eur\":0.01,\"cny\":0.08,\"gbp\":0.008,\"jpy\":1.5}}";
    }

    @Test void loadsBothDaysFromBackupWhenOneOfficialDayFails() {
        LocalDate date = LocalDate.of(2024, 4, 2);
        server.expect(requestTo(CbrClient.SOURCE + "?date_req=02/04/2024"))
                .andRespond(withSuccess(XmlFixtures.rates(date, "92,0000"), MediaType.APPLICATION_XML));
        server.expect(requestTo(CbrClient.SOURCE + "?date_req=01/04/2024")).andRespond(withServerError());
        server.expect(requestTo(BackupCurrencyClient.CDN + "latest" + PATH))
                .andRespond(withSuccess(json("2024-04-02", "0.01"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(BackupCurrencyClient.CDN + "2024-04-01" + PATH))
                .andRespond(withSuccess(json("2024-04-01", "0.0125"), MediaType.APPLICATION_JSON));
        var result = new CbrClient(rest, new CbrXmlParser(), backup, true).fetchDay(date);
        assertThat(result.current().quotes().get(CurrencyCode.USD).rate()).isEqualByComparingTo("100");
        assertThat(result.previous().quotes().get(CurrencyCode.USD).rate()).isEqualByComparingTo("80");
        assertThat(result.current().quotes().get(CurrencyCode.JPY).rate()).isEqualByComparingTo("0.66666667");
        assertThat(result.current().sourceUrl()).startsWith(BackupCurrencyClient.CDN);
        server.verify();
    }

    @Test void usesSecondHostWhenCdnFails() {
        server.expect(requestTo(BackupCurrencyClient.CDN + "2024-04-01" + PATH)).andRespond(withServerError());
        server.expect(requestTo("https://2024-04-01.currency-api.pages.dev" + PATH))
                .andRespond(withSuccess(json("2024-04-01", "0.01"), MediaType.APPLICATION_JSON));
        var result = backup.fetch(LocalDate.of(2024, 4, 1));
        assertThat(result.sourceUrl()).contains("currency-api.pages.dev");
        assertThat(result.rateDate()).isEqualTo(LocalDate.of(2024, 4, 1));
        server.verify();
    }

    @Test void rejectsFutureDataFromBothHosts() {
        server.expect(requestTo(BackupCurrencyClient.CDN + "latest" + PATH))
                .andRespond(withSuccess(json("2024-04-03", "0.01"), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://latest.currency-api.pages.dev" + PATH))
                .andRespond(withSuccess(json("2024-04-03", "0.01"), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> backup.fetch(LocalDate.of(2024, 4, 2))).isInstanceOf(SourceException.class);
        server.verify();
    }

    @Test void rejectsZeroInsteadOfDividingByZero() {
        server.expect(requestTo(BackupCurrencyClient.CDN + "latest" + PATH))
                .andRespond(withSuccess(json("2024-04-02", "0"), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://latest.currency-api.pages.dev" + PATH)).andRespond(withServerError());
        assertThatThrownBy(() -> backup.fetch(LocalDate.of(2024, 4, 2))).isInstanceOf(SourceException.class);
        server.verify();
    }
}
