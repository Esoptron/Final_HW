package ru.esoptron.currency;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.RestTemplate;
import ru.esoptron.currency.client.CbrClient;
import ru.esoptron.currency.dto.CollectionJob;
import ru.esoptron.currency.repository.ExchangeRateRepository;
import ru.esoptron.currency.service.CollectionService;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:h2:mem:rates-test;DB_CLOSE_DELAY=-1",
        "collector.enabled=false", "rates.fallback-enabled=false", "collector.audit-file=target/test-collections.log"})
@AutoConfigureMockMvc
class ApiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired RestTemplate restTemplate;
    @Autowired ExchangeRateRepository repository;
    @Autowired CollectionService collections;
    private MockRestServiceServer source;
    private final LocalDate date = LocalDate.of(2024, 4, 2);

    @BeforeEach void prepare() {
        repository.deleteAll();
        source = MockRestServiceServer.bindTo(restTemplate).ignoreExpectOrder(true).build();
    }

    private String url(LocalDate day) {
        return CbrClient.SOURCE + "?date_req=" + day.format(DateTimeFormatter.ofPattern("dd/MM/uuuu"));
    }

    private void expectRates(LocalDate requested, LocalDate actual, String usd) {
        source.expect(requestTo(url(requested))).andRespond(withSuccess(XmlFixtures.rates(actual, usd), MediaType.APPLICATION_XML));
    }

    private UUID submit(String body) throws Exception {
        var response = mvc.perform(post("/api/collections").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted()).andExpect(header().exists("Location")).andReturn();
        return UUID.fromString(json.readTree(response.getResponse().getContentAsString()).get("id").asText());
    }

    private CollectionJob waitFor(UUID id) {
        await().atMost(Duration.ofSeconds(8)).until(() -> collections.get(id).terminal());
        return collections.get(id);
    }

    @Test void collectsPersistsAndReturnsDailyChanges() throws Exception {
        expectRates(date, date, "92,0000");
        expectRates(date.minusDays(1), date.minusDays(1), "90,0000");
        UUID id = submit("{\"date\":\"2024-04-02\",\"sourceUrl\":\"" + CbrClient.SOURCE + "\"}");
        assertThat(waitFor(id).status()).isEqualTo(CollectionJob.Status.COMPLETED);
        mvc.perform(get("/api/collections/" + id)).andExpect(jsonPath("$.savedRates").value(5));
        mvc.perform(get("/api/rates/USD?date=2024-04-02"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rate").value(92.0))
                .andExpect(jsonPath("$.change").value(2.0)).andExpect(jsonPath("$.changePercent").value(2.222222))
                .andExpect(jsonPath("$.name").value("Доллар США"));
        mvc.perform(get("/answer?sortBy=RATE&direction=DESC&size=2"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3)).andExpect(jsonPath("$.content[0].currency").value("GBP"));
        mvc.perform(get("/answer?date=2024-04-02&currency=JPY"))
                .andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].rate").value(0.6));
        mvc.perform(get("/answer?page=2147483647&size=100"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").isEmpty());
        source.verify();
    }

    @Test void repeatedCollectionUpdatesInsteadOfDuplicatingRows() throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            source.reset();
            expectRates(date, date, attempt == 0 ? "92,0000" : "93,0000");
            expectRates(date.minusDays(1), date.minusDays(1), "90,0000");
            assertThat(waitFor(submit("{\"date\":\"2024-04-02\"}")).status()).isEqualTo(CollectionJob.Status.COMPLETED);
            source.verify();
        }
        assertThat(repository.count()).isEqualTo(5);
        mvc.perform(get("/api/rates/USD?date=2024-04-02")).andExpect(jsonPath("$.rate").value(93.0));
    }

    @Test void retainsEffectiveDateAndZeroChangeOnNonTradingDay() throws Exception {
        LocalDate sunday = LocalDate.of(2024, 4, 7);
        LocalDate saturday = sunday.minusDays(1);
        expectRates(sunday, saturday, "92,0000");
        expectRates(saturday, saturday, "92,0000");
        waitFor(submit("{\"date\":\"2024-04-07\"}"));
        mvc.perform(get("/api/rates/USD?date=2024-04-07"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.rateDate").value("2024-04-06"))
                .andExpect(jsonPath("$.requestedDate").value("2024-04-07"))
                .andExpect(jsonPath("$.change").value(0.0));
    }

    @Test void sourceFailureDoesNotSavePartialBatch() throws Exception {
        expectRates(date, date, "92,0000");
        source.expect(requestTo(url(date.minusDays(1)))).andRespond(withServerError());
        var job = waitFor(submit("{\"date\":\"2024-04-02\"}"));
        assertThat(job.status()).isEqualTo(CollectionJob.Status.FAILED);
        assertThat(job.error()).contains("ЦБ РФ недоступен");
        mvc.perform(get("/api/collections/latest")).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(job.id().toString()))
                .andExpect(jsonPath("$.status").value("FAILED"));
        assertThat(repository.count()).isZero();
        source.verify();
    }

    @Test void concurrentRequestsForSameDateShareOneTask() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        source.expect(requestTo(url(date))).andRespond(request -> {
            started.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timed out");
            } catch (InterruptedException ex) { throw new IllegalStateException(ex); }
            return withSuccess(XmlFixtures.rates(date, "92,0000"), MediaType.APPLICATION_XML).createResponse(request);
        });
        expectRates(date.minusDays(1), date.minusDays(1), "90,0000");
        UUID first = submit("{\"date\":\"2024-04-02\"}");
        try {
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(submit("{\"date\":\"2024-04-02\"}")).isEqualTo(first);
        } finally { release.countDown(); }
        assertThat(waitFor(first).status()).isEqualTo(CollectionJob.Status.COMPLETED);
        assertThat(repository.count()).isEqualTo(5);
        source.verify();
    }

    @Test void validatesInputAndReportsMissingData() throws Exception {
        mvc.perform(get("/api/currencies")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(5));
        for (String path : new String[]{"/answer?size=0", "/answer?page=-1", "/answer?sortBy=oops",
                "/answer?currency=BTC", "/answer?date=bad", "/api/rates/USD"}) {
            mvc.perform(get(path)).andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/rates/USD?date=2024-04-02")).andExpect(status().isNotFound());
        mvc.perform(get("/api/collections/" + UUID.randomUUID())).andExpect(status().isNotFound());
        for (String body : new String[]{"{", "{\"date\":\"2999-01-01\"}", "{\"date\":\"1999-01-01\"}",
                "{\"sourceUrl\":\"http://localhost:1234/private\"}"}) {
            mvc.perform(post("/api/collections").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/answer")).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    }
}
