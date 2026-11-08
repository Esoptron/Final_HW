package ru.esoptron.currency.client;

import org.junit.jupiter.api.Test;
import ru.esoptron.currency.XmlFixtures;
import ru.esoptron.currency.model.CurrencyCode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import static org.assertj.core.api.Assertions.*;

class CbrXmlParserTest {
    private final CbrXmlParser parser = new CbrXmlParser();
    private final LocalDate date = LocalDate.of(2024, 4, 2);

    @Test void normalizesNominalAndReadsCyrillicInSourceEncoding() {
        var result = parser.parse(XmlFixtures.rates(date, "92,0000"));
        assertThat(result.rateDate()).isEqualTo(date);
        assertThat(result.quotes()).hasSize(5);
        assertThat(result.quotes().get(CurrencyCode.USD).name()).isEqualTo("Доллар США");
        assertThat(result.quotes().get(CurrencyCode.JPY).nominal()).isEqualTo(100);
        assertThat(result.quotes().get(CurrencyCode.JPY).rate()).isEqualByComparingTo("0.6");
        assertThatThrownBy(() -> result.quotes().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test void rejectsIncompleteResponse() {
        assertThatThrownBy(() -> parser.parse("<ValCurs Date=\"02.04.2024\"/>".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(SourceException.class).hasMessageContaining("пять");
    }

    @Test void rejectsExternalEntities() {
        String xml = "<!DOCTYPE x [<!ENTITY secret SYSTEM 'file:///no-such-file'>]>"
                + "<ValCurs Date='02.04.2024'>&secret;</ValCurs>";
        assertThatThrownBy(() -> parser.parse(xml.getBytes(StandardCharsets.UTF_8))).isInstanceOf(SourceException.class);
    }

    @Test void rejectsNonPositiveRate() {
        assertThatThrownBy(() -> parser.parse(XmlFixtures.rates(date, "0,0000")))
                .isInstanceOf(SourceException.class).hasMessageContaining("USD");
    }

    @Test void rejectsEmptyAndHtmlResponses() {
        assertThatThrownBy(() -> parser.parse(null)).isInstanceOf(SourceException.class);
        assertThatThrownBy(() -> parser.parse("<html/>".getBytes(StandardCharsets.UTF_8))).isInstanceOf(SourceException.class);
    }
}
