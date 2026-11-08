package ru.esoptron.currency;

import java.nio.charset.Charset;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

public final class XmlFixtures {
    private XmlFixtures() { }
    public static byte[] rates(LocalDate date, String usd) {
        String xml = """
                <?xml version="1.0" encoding="windows-1251"?>
                <ValCurs Date="%s" name="Foreign Currency Market">
                  <Valute ID="R01235"><CharCode>USD</CharCode><Nominal>1</Nominal><Name>Доллар США</Name><Value>%s</Value></Valute>
                  <Valute ID="R01239"><CharCode>EUR</CharCode><Nominal>1</Nominal><Name>Евро</Name><Value>100,0000</Value></Valute>
                  <Valute ID="R01375"><CharCode>CNY</CharCode><Nominal>1</Nominal><Name>Юань</Name><Value>12,5000</Value></Valute>
                  <Valute ID="R01035"><CharCode>GBP</CharCode><Nominal>1</Nominal><Name>Фунт стерлингов</Name><Value>115,0000</Value></Valute>
                  <Valute ID="R01820"><CharCode>JPY</CharCode><Nominal>100</Nominal><Name>Иена</Name><Value>60,0000</Value></Valute>
                  <Valute ID="R01010"><CharCode>AUD</CharCode><Nominal>1</Nominal><Name>Австралийский доллар</Name><Value>55,0000</Value></Valute>
                </ValCurs>
                """.formatted(date.format(DateTimeFormatter.ofPattern("dd.MM.uuuu")), usd);
        return xml.getBytes(Charset.forName("windows-1251"));
    }
}
