package ru.esoptron.currency.dto;

import java.time.LocalDate;
import java.net.URI;
import ru.esoptron.currency.client.CbrClient;

public record CollectionRequest(LocalDate date, String sourceUrl) {
    public void validateSource() {
        if (sourceUrl == null) return;
        URI uri;
        try { uri = URI.create(sourceUrl); }
        catch (IllegalArgumentException ex) { throw new IllegalArgumentException("Некорректная ссылка источника"); }
        if (!"https".equalsIgnoreCase(uri.getScheme())
                || !("www.cbr.ru".equalsIgnoreCase(uri.getHost()) || "cbr.ru".equalsIgnoreCase(uri.getHost()))
                || !"/scripts/XML_daily.asp".equals(uri.getPath()) || uri.getQuery() != null
                || uri.getFragment() != null || uri.getUserInfo() != null || uri.getPort() != -1) {
            throw new IllegalArgumentException("Поддерживается источник " + CbrClient.SOURCE
                    + " без параметров; дату передайте в поле date");
        }
    }
}
