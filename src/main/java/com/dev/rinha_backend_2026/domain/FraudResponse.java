package com.dev.rinha_backend_2026.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

public record FraudResponse(
        boolean approved,
        @JsonProperty("fraud_score") double fraudScore
) {}
