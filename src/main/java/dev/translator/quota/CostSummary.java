package dev.translator.quota;

import java.math.BigDecimal;

public record CostSummary(BigDecimal totalCostUsd, long unpricedRequests) {}
