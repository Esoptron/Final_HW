package ru.esoptron.currency.client;

import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;
import ru.esoptron.currency.model.CurrencyCode;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;

@Component
public class CbrXmlParser {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.uuuu");

    public RateSnapshot parse(byte[] xml) {
        if (xml == null || xml.length == 0 || xml.length > 1_000_000) {
            throw new SourceException("Источник вернул пустой или слишком большой документ");
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler() {
                @Override public void fatalError(org.xml.sax.SAXParseException ex) throws SAXException {
                    throw ex;
                }
            });
            Element root = builder.parse(new ByteArrayInputStream(xml)).getDocumentElement();
            if (!"ValCurs".equals(root.getTagName())) {
                throw new SourceException("В ответе отсутствует таблица курсов ЦБ РФ");
            }
            LocalDate rateDate = LocalDate.parse(root.getAttribute("Date"), DATE);
            var quotes = new EnumMap<CurrencyCode, RateSnapshot.Quote>(CurrencyCode.class);
            NodeList values = root.getElementsByTagName("Valute");
            for (int i = 0; i < values.getLength(); i++) {
                Element entry = (Element) values.item(i);
                CurrencyCode code;
                try {
                    code = CurrencyCode.valueOf(text(entry, "CharCode"));
                } catch (IllegalArgumentException ignored) {
                    continue;
                }
                int nominal = Integer.parseInt(text(entry, "Nominal"));
                BigDecimal value = new BigDecimal(text(entry, "Value").replace(',', '.'));
                if (nominal <= 0 || value.signum() <= 0) {
                    throw new SourceException("Некорректный курс " + code);
                }
                BigDecimal rate = value.divide(BigDecimal.valueOf(nominal), 8, RoundingMode.HALF_UP);
                if (quotes.put(code, new RateSnapshot.Quote(text(entry, "Name"), nominal, rate)) != null) {
                    throw new SourceException("Дублирующийся код валюты " + code);
                }
            }
            if (quotes.size() != CurrencyCode.values().length) {
                throw new SourceException("Источник вернул не все пять поддерживаемых валют");
            }
            return new RateSnapshot(rateDate, quotes);
        } catch (SourceException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new SourceException("Не удалось разобрать XML с курсами ЦБ РФ", ex);
        }
    }

    private String text(Element entry, String tag) {
        var nodes = entry.getElementsByTagName(tag);
        if (nodes.getLength() != 1) throw new SourceException("Отсутствует поле " + tag);
        return nodes.item(0).getTextContent().trim();
    }
}
