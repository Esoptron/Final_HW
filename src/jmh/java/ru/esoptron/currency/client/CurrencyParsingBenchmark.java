package ru.esoptron.currency.client;

import org.openjdk.jmh.annotations.*;
import org.w3c.dom.Element;
import ru.esoptron.currency.model.CurrencyCode;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collector;

@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class CurrencyParsingBenchmark {
    private static final Map<String, CurrencyCode> SUPPORTED = Map.of(
            "USD", CurrencyCode.USD, "EUR", CurrencyCode.EUR, "CNY", CurrencyCode.CNY,
            "GBP", CurrencyCode.GBP, "JPY", CurrencyCode.JPY);
    private final CbrXmlParser parser = new CbrXmlParser();
    private byte[] xml;
    private List<RawQuote> entries;

    @Setup
    public void prepare() throws Exception {
        xml = new CbrFixtureController(0).xml("02/04/2024").getBytes(StandardCharsets.UTF_8);
        var document = DocumentBuilderFactory.newDefaultInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml));
        var elements = document.getElementsByTagName("Valute");
        entries = new ArrayList<>(elements.getLength());
        for (int i = 0; i < elements.getLength(); i++) {
            Element element = (Element) elements.item(i);
            entries.add(new RawQuote(text(element, "CharCode"), text(element, "Name"),
                    text(element, "Nominal"), text(element, "Value")));
        }
        Map<CurrencyCode, RateSnapshot.Quote> expected = parser.parse(xml).quotes();
        if (!expected.equals(forLoop()) || !expected.equals(stream()) || !expected.equals(parallelStream())) {
            throw new IllegalStateException("Результаты вариантов парсинга различаются");
        }
    }

    @Benchmark
    public RateSnapshot fullXml() {
        return parser.parse(xml);
    }

    @Benchmark
    public Map<CurrencyCode, RateSnapshot.Quote> forLoop() {
        var quotes = new EnumMap<CurrencyCode, RateSnapshot.Quote>(CurrencyCode.class);
        for (RawQuote entry : entries) putQuote(quotes, entry);
        return quotes;
    }

    @Benchmark
    public Map<CurrencyCode, RateSnapshot.Quote> stream() {
        return entries.stream().collect(quotes());
    }

    @Benchmark
    public Map<CurrencyCode, RateSnapshot.Quote> parallelStream() {
        return entries.parallelStream().collect(quotes());
    }

    private static Collector<RawQuote, EnumMap<CurrencyCode, RateSnapshot.Quote>,
            EnumMap<CurrencyCode, RateSnapshot.Quote>> quotes() {
        return Collector.of(() -> new EnumMap<>(CurrencyCode.class), CurrencyParsingBenchmark::putQuote,
                (left, right) -> {
                    left.putAll(right);
                    return left;
                });
    }

    private static void putQuote(Map<CurrencyCode, RateSnapshot.Quote> quotes, RawQuote entry) {
        CurrencyCode code = SUPPORTED.get(entry.code());
        if (code == null) return;
        int nominal = Integer.parseInt(entry.nominal());
        BigDecimal normalized = new BigDecimal(entry.value().replace(',', '.'))
                .divide(BigDecimal.valueOf(nominal), 8, RoundingMode.HALF_UP);
        quotes.put(code, new RateSnapshot.Quote(entry.name(), nominal, normalized));
    }

    private static String text(Element element, String field) {
        return element.getElementsByTagName(field).item(0).getTextContent();
    }

    private record RawQuote(String code, String name, String nominal, String value) { }
}
