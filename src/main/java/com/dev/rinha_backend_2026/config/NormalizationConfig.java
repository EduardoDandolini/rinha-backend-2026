package com.dev.rinha_backend_2026.config;

import com.fasterxml.jackson.annotation.JsonProperty;

public record NormalizationConfig(
        @JsonProperty("max_amount") double maxAmount,
        @JsonProperty("max_installments") double maxInstallments,
        @JsonProperty("amount_vs_avg_ratio") double amountVsAvgRatio,
        @JsonProperty("max_minutes") double maxMinutes,
        @JsonProperty("max_km") double maxKm,
        @JsonProperty("max_tx_count_24h") double maxTxCount24h,
        @JsonProperty("max_merchant_avg_amount") double maxMerchantAvgAmount
) {}
