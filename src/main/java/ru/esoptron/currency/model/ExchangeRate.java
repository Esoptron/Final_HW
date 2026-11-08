package ru.esoptron.currency.model;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "exchange_rates", uniqueConstraints = @UniqueConstraint(columnNames = {"requested_date", "currency_code"}))
public class ExchangeRate {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "requested_date", nullable = false)
    private LocalDate requestedDate;
    @Column(nullable = false)
    private LocalDate rateDate;
    @Column(nullable = false)
    private LocalDate previousRateDate;
    @Enumerated(EnumType.STRING) @Column(name = "currency_code", nullable = false, length = 3)
    private CurrencyCode currencyCode;
    @Column(nullable = false)
    private String name;
    @Column(nullable = false)
    private int nominal;
    @Column(nullable = false, precision = 24, scale = 8)
    private BigDecimal rate;
    @Column(nullable = false, precision = 24, scale = 8)
    private BigDecimal previousRate;
    @Column(nullable = false)
    private Instant collectedAt;

    protected ExchangeRate() { }

    public ExchangeRate(LocalDate requestedDate, LocalDate rateDate, LocalDate previousRateDate,
                        CurrencyCode currencyCode, String name, int nominal, BigDecimal rate,
                        BigDecimal previousRate, Instant collectedAt) {
        this.requestedDate = requestedDate;
        this.rateDate = rateDate;
        this.previousRateDate = previousRateDate;
        this.currencyCode = currencyCode;
        this.name = name;
        this.nominal = nominal;
        this.rate = rate;
        this.previousRate = previousRate;
        this.collectedAt = collectedAt;
    }

    public void refreshFrom(ExchangeRate other) {
        this.rateDate = other.rateDate;
        this.previousRateDate = other.previousRateDate;
        this.name = other.name;
        this.nominal = other.nominal;
        this.rate = other.rate;
        this.previousRate = other.previousRate;
        this.collectedAt = other.collectedAt;
    }

    public LocalDate getRequestedDate() { return requestedDate; }
    public LocalDate getRateDate() { return rateDate; }
    public LocalDate getPreviousRateDate() { return previousRateDate; }
    public CurrencyCode getCurrencyCode() { return currencyCode; }
    public String getName() { return name; }
    public int getNominal() { return nominal; }
    public BigDecimal getRate() { return rate; }
    public BigDecimal getPreviousRate() { return previousRate; }
    public Instant getCollectedAt() { return collectedAt; }
}
