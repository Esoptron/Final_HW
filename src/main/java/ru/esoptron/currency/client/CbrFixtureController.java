package ru.esoptron.currency.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

@RestController
@Profile("fixture")
class CbrFixtureController {
    private static final DateTimeFormatter REQUEST_DATE = DateTimeFormatter.ofPattern("dd/MM/uuuu");
    private static final DateTimeFormatter XML_DATE = DateTimeFormatter.ofPattern("dd.MM.uuuu");
    private static final List<String> OTHER_CURRENCIES = List.of(
            "AUD", "AZN", "AMD", "BYN", "BGN", "BRL", "HUF", "VND", "HKD", "GEL",
            "DKK", "AED", "EGP", "INR", "IDR", "KZT", "CAD", "QAR", "KGS", "KRW",
            "CUP", "MDL", "NZD", "NOK", "PLN", "RON", "XDR", "SGD", "TJS", "THB",
            "TRY", "TMT", "UZS", "UAH", "CZK", "SEK", "CHF", "ZAR");
    private static final String ENTRIES = entries();
    private final long delayMs;

    CbrFixtureController(@Value("${fixture.delay-ms:5}") long delayMs) {
        this.delayMs = delayMs;
    }

    @GetMapping(value = "/fixture/cbr.xml", produces = "application/xml;charset=UTF-8")
    String xml(@RequestParam("date_req") String date) throws InterruptedException {
        Thread.sleep(delayMs);
        LocalDate requested = LocalDate.parse(date, REQUEST_DATE);
        if (requested.equals(LocalDate.of(2001, 1, 1))) return "<unavailable/>";
        return "<ValCurs Date=\"" + requested.format(XML_DATE) + "\">" + ENTRIES + "</ValCurs>";
    }

    private static String entries() {
        var result = new StringBuilder();
        for (String code : OTHER_CURRENCIES) result.append(entry(code, code, 1, "10,0000"));
        return result.append(entry("USD", "Доллар США", 1, "93,0000"))
                .append(entry("EUR", "Евро", 1, "101,0000"))
                .append(entry("CNY", "Юань", 1, "12,8000"))
                .append(entry("GBP", "Фунт стерлингов", 1, "118,0000"))
                .append(entry("JPY", "Иена", 100, "61,0000")).toString();
    }

    private static String entry(String code, String name, int nominal, String value) {
        return "<Valute><CharCode>" + code + "</CharCode><Nominal>" + nominal + "</Nominal><Name>"
                + name + "</Name><Value>" + value + "</Value></Valute>";
    }
}
