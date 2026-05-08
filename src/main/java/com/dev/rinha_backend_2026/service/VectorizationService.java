package com.dev.rinha_backend_2026.service;

import com.dev.rinha_backend_2026.config.NormalizationConfig;
import com.dev.rinha_backend_2026.domain.TransactionRequest;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;

@Service
public class VectorizationService {

    private final NormalizationConfig norm;
    private final Map<String, Float> mccRisk;

    public VectorizationService(NormalizationConfig norm, Map<String, Float> mccRisk) {
        this.norm = norm;
        this.mccRisk = mccRisk;
    }

    public float[] vectorize(TransactionRequest tx) {
        float[] v = new float[14];

        v[0] = clamp(tx.transaction().amount() / norm.maxAmount());
        v[1] = clamp(tx.transaction().installments() / norm.maxInstallments());
        v[2] = clamp((tx.transaction().amount() / tx.customer().avgAmount()) / norm.amountVsAvgRatio());

        ZonedDateTime dt = Instant.parse(tx.transaction().requestedAt()).atZone(ZoneOffset.UTC);
        v[3] = dt.getHour() / 23f;
        // Monday=1 in DayOfWeek, but spec says Monday=0, Sunday=6
        v[4] = (dt.getDayOfWeek().getValue() - 1) / 6f;

        if (tx.lastTransaction() == null) {
            v[5] = -1f;
            v[6] = -1f;
        } else {
            Instant current = Instant.parse(tx.transaction().requestedAt());
            Instant last = Instant.parse(tx.lastTransaction().timestamp());
            long minutes = Math.abs(ChronoUnit.MINUTES.between(last, current));
            v[5] = clamp(minutes / norm.maxMinutes());
            v[6] = clamp(tx.lastTransaction().kmFromCurrent() / norm.maxKm());
        }

        v[7]  = clamp(tx.terminal().kmFromHome() / norm.maxKm());
        v[8]  = clamp(tx.customer().txCount24h() / norm.maxTxCount24h());
        v[9]  = tx.terminal().isOnline() ? 1f : 0f;
        v[10] = tx.terminal().cardPresent() ? 1f : 0f;
        v[11] = tx.customer().knownMerchants().contains(tx.merchant().id()) ? 0f : 1f;
        v[12] = mccRisk.getOrDefault(tx.merchant().mcc(), 0.5f);
        v[13] = clamp(tx.merchant().avgAmount() / norm.maxMerchantAvgAmount());

        return v;
    }

    private static float clamp(double x) {
        return (float) Math.min(1.0, Math.max(0.0, x));
    }
}
