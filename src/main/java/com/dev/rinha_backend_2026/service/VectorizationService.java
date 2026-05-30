package com.dev.rinha_backend_2026.service;

import com.dev.rinha_backend_2026.config.NormalizationConfig;
import com.dev.rinha_backend_2026.domain.TransactionRequest;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class VectorizationService {

    private final NormalizationConfig norm;
    private final Map<String, Float> mccRisk;

    public VectorizationService(NormalizationConfig norm, Map<String, Float> mccRisk) {
        this.norm = norm;
        this.mccRisk = mccRisk;
    }

    public void vectorize(TransactionRequest tx, float[] v) {
        v[0] = clamp(tx.transaction().amount() / norm.maxAmount());
        v[1] = clamp(tx.transaction().installments() / norm.maxInstallments());
        v[2] = clamp((tx.transaction().amount() / tx.customer().avgAmount()) / norm.amountVsAvgRatio());

        long curEpochSec = parseIsoEpochSec(tx.transaction().requestedAt());
        long epochDays   = Math.floorDiv(curEpochSec, 86400L);
        long secInDay    = curEpochSec - epochDays * 86400L;
        int  hour        = (int) (secInDay / 3600L);
        int  dayOfWeek0  = (int) Math.floorMod(epochDays + 3L, 7L);
        v[3] = hour / 23f;
        v[4] = dayOfWeek0 / 6f;

        if (tx.lastTransaction() == null) {
            v[5] = -1f;
            v[6] = -1f;
        } else {
            long lastEpochSec = parseIsoEpochSec(tx.lastTransaction().timestamp());
            long minutes      = Math.abs(curEpochSec - lastEpochSec) / 60L;
            v[5] = clamp(minutes / norm.maxMinutes());
            v[6] = clamp(tx.lastTransaction().kmFromCurrent() / norm.maxKm());
        }

        v[7]  = clamp(tx.terminal().kmFromHome() / norm.maxKm());
        v[8]  = clamp(tx.customer().txCount24h() / norm.maxTxCount24h());
        v[9]  = tx.terminal().isOnline() ? 1f : 0f;
        v[10] = tx.terminal().cardPresent() ? 1f : 0f;
        v[11] = containsMerchant(tx.customer().knownMerchants(), tx.merchant().id()) ? 0f : 1f;
        v[12] = mccRisk.getOrDefault(tx.merchant().mcc(), 0.5f);
        v[13] = clamp(tx.merchant().avgAmount() / norm.maxMerchantAvgAmount());
    }

    private static long parseIsoEpochSec(String s) {
        int year   = digit4(s, 0);
        int month  = digit2(s, 5);
        int day    = digit2(s, 8);
        int hour   = digit2(s, 11);
        int minute = digit2(s, 14);
        int second = digit2(s, 17);
        long epochDay = daysFromCivil(year, month, day);
        return epochDay * 86400L + hour * 3600L + minute * 60L + second;
    }

    private static int digit2(String s, int i) {
        return (s.charAt(i) - '0') * 10 + (s.charAt(i + 1) - '0');
    }

    private static int digit4(String s, int i) {
        return (s.charAt(i)     - '0') * 1000
             + (s.charAt(i + 1) - '0') * 100
             + (s.charAt(i + 2) - '0') * 10
             + (s.charAt(i + 3) - '0');
    }

    private static long daysFromCivil(int y, int m, int d) {
        y -= m <= 2 ? 1 : 0;
        int era = (y >= 0 ? y : y - 399) / 400;
        int yoe = y - era * 400;
        int doy = (153 * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1;
        int doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return (long) era * 146097L + doe - 719468L;
    }

    private static float clamp(double x) {
        return (float) Math.min(1.0, Math.max(0.0, x));
    }

    private static boolean containsMerchant(String[] arr, String target) {
        if (arr == null) return false;
        for (int i = 0; i < arr.length; i++) {
            if (target.equals(arr[i])) return true;
        }
        return false;
    }
}
