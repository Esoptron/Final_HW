package ru.esoptron.currency.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import ru.esoptron.currency.model.CurrencyCode;
import ru.esoptron.currency.model.ExchangeRate;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ExchangeRateRepository extends JpaRepository<ExchangeRate, Long> {
    Optional<ExchangeRate> findByRequestedDateAndCurrencyCode(LocalDate date, CurrencyCode code);
    List<ExchangeRate> findByRequestedDate(LocalDate date);
    @Query("select max(r.requestedDate) from ExchangeRate r")
    Optional<LocalDate> findLatestDate();
}
