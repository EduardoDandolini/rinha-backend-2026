package com.dev.rinha_backend_2026.service;

import org.springframework.stereotype.Service;

@Service
public class KnnSearchService {

    public static final int K = 5;
    public static final int DIM = 14;
    private static final float FRAUD_THRESHOLD = 0.6f;

    // Flat int8 vectors: vectors[i * DIM .. i * DIM + DIM - 1]
    private byte[] vectors;
    private boolean[] isFraud;
    private int size;

    private HnswIndex hnsw;

    public void init(byte[] vectors, boolean[] isFraud, int size) {
        this.vectors = vectors;
        this.isFraud = isFraud;
        this.size = size;
    }

    public void setHnsw(HnswIndex hnsw) {
        this.hnsw = hnsw;
    }

    public HnswIndex getHnsw() { return hnsw; }

    public boolean isReady() {
        return hnsw != null;
    }

    public byte[] getVectors() { return vectors; }
    public boolean[] getIsFraud() { return isFraud; }
    public int getSize() { return size; }

    // max squared distance per dimension: (127 - (-128))^2 = 65025
    // max total for DIM=14: 14 * 65025 = 910_350, fits safely in int
    public static FraudResult evaluate(byte[] query, byte[] vectors, boolean[] isFraud, int size) {
        // Cache query bytes as locals to keep them in registers across the loop
        int q0  = query[0],  q1  = query[1],  q2  = query[2],  q3  = query[3],
            q4  = query[4],  q5  = query[5],  q6  = query[6],  q7  = query[7],
            q8  = query[8],  q9  = query[9],  q10 = query[10], q11 = query[11],
            q12 = query[12], q13 = query[13];

        // Inline K-heap: parallel arrays, no object allocation
        int t0 = Integer.MAX_VALUE, t1 = Integer.MAX_VALUE, t2 = Integer.MAX_VALUE,
            t3 = Integer.MAX_VALUE, t4 = Integer.MAX_VALUE;
        int i0 = -1, i1 = -1, i2 = -1, i3 = -1, i4 = -1;
        int worstDist = Integer.MAX_VALUE;

        for (int i = 0, base = 0; i < size; i++, base += DIM) {
            int d0  = q0  - vectors[base],      d1  = q1  - vectors[base + 1],
                d2  = q2  - vectors[base + 2],  d3  = q3  - vectors[base + 3],
                d4  = q4  - vectors[base + 4],  d5  = q5  - vectors[base + 5],
                d6  = q6  - vectors[base + 6],  d7  = q7  - vectors[base + 7],
                d8  = q8  - vectors[base + 8],  d9  = q9  - vectors[base + 9],
                d10 = q10 - vectors[base + 10], d11 = q11 - vectors[base + 11],
                d12 = q12 - vectors[base + 12], d13 = q13 - vectors[base + 13];
            int dist = d0*d0 + d1*d1 + d2*d2 + d3*d3 + d4*d4 + d5*d5 + d6*d6
                     + d7*d7 + d8*d8 + d9*d9 + d10*d10 + d11*d11 + d12*d12 + d13*d13;

            if (dist < worstDist) {
                // Replace the slot with the current worst distance
                if      (t0 == worstDist) { t0 = dist; i0 = i; }
                else if (t1 == worstDist) { t1 = dist; i1 = i; }
                else if (t2 == worstDist) { t2 = dist; i2 = i; }
                else if (t3 == worstDist) { t3 = dist; i3 = i; }
                else                      { t4 = dist; i4 = i; }
                // Recompute worst
                worstDist = Math.max(Math.max(Math.max(Math.max(t0, t1), t2), t3), t4);
            }
        }

        int fraudCount = 0;
        if (i0 >= 0 && isFraud[i0]) fraudCount++;
        if (i1 >= 0 && isFraud[i1]) fraudCount++;
        if (i2 >= 0 && isFraud[i2]) fraudCount++;
        if (i3 >= 0 && isFraud[i3]) fraudCount++;
        if (i4 >= 0 && isFraud[i4]) fraudCount++;

        double score = (double) fraudCount / K;
        return new FraudResult(score < FRAUD_THRESHOLD, score);
    }

    public static byte quantize(float x) {
        if (x <= -1f) return (byte) -128;
        return (byte) Math.round(x * 127f);
    }

    public static byte[] quantizeVector(float[] vec) {
        byte[] result = new byte[vec.length];
        for (int i = 0; i < vec.length; i++) {
            result[i] = quantize(vec[i]);
        }
        return result;
    }

    public record FraudResult(boolean approved, double fraudScore) {}
}
