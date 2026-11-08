package ru.esoptron.currency.dto;

import java.util.List;

public record RatePage(List<RateResponse> content, int page, int size, long totalElements, int totalPages) { }
