package com.dev.rinha_backend_2026.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TransactionRequest(
        String id,
        Transaction transaction,
        Customer customer,
        Merchant merchant,
        Terminal terminal,
        @JsonProperty("last_transaction") LastTransaction lastTransaction
) {

    public record Transaction(
            double amount,
            int installments,
            @JsonProperty("requested_at") String requestedAt
    ) {}

    public record Customer(
            @JsonProperty("avg_amount") double avgAmount,
            @JsonProperty("tx_count_24h") int txCount24h,
            @JsonProperty("known_merchants") String[] knownMerchants
    ) {}

    public record Merchant(
            String id,
            String mcc,
            @JsonProperty("avg_amount") double avgAmount
    ) {}

    public record Terminal(
            @JsonProperty("is_online") boolean isOnline,
            @JsonProperty("card_present") boolean cardPresent,
            @JsonProperty("km_from_home") double kmFromHome
    ) {}

    public record LastTransaction(
            String timestamp,
            @JsonProperty("km_from_current") double kmFromCurrent
    ) {}
}
