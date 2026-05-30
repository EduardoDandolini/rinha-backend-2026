package com.dev.rinha_backend_2026.service;

import org.springframework.stereotype.Service;

@Service
public class KnnSearchService {

    public static final int   K         = 5;
    public static final int   DIM       = 14;
    public static final float THRESHOLD = 0.6f;
    public static final int   SCALE     = 10_000;

    private static final boolean NOPRUNE = Boolean.getBoolean("ivf.noprune");

    private volatile short[] centroids;
    private volatile short[] bboxMin;
    private volatile short[] bboxMax;
    private volatile int[]   offsets;
    private volatile short[] rows;
    private volatile byte[]  labels;
    private volatile int[]   origIds;
    private volatile int     nRefs;
    private volatile int     nClusters;

    public void setDataset(short[] centroids, short[] bboxMin, short[] bboxMax,
                           int[] offsets, short[] rows, byte[] labels,
                           int[] origIds, int nRefs, int nClusters) {
        this.centroids = centroids;
        this.bboxMin   = bboxMin;
        this.bboxMax   = bboxMax;
        this.offsets   = offsets;
        this.rows      = rows;
        this.labels    = labels;
        this.origIds   = origIds;
        this.nRefs     = nRefs;
        this.nClusters = nClusters;
    }

    public boolean isReady()     { return rows != null; }
    public int     getRefCount() { return nRefs; }

    private static final ThreadLocal<float[]> THREAD_QUERY =
        ThreadLocal.withInitial(() -> new float[DIM]);

    public static float[] threadQuery() { return THREAD_QUERY.get(); }

    public static short quantize(float x) {
        int v = Math.round(x * SCALE);
        if (v >  SCALE) return (short)  SCALE;
        if (v < -SCALE) return (short) -SCALE;
        return (short) v;
    }

    public FraudResult search(float[] query) {
        final short[] R = rows;
        if (R == null) return new FraudResult(true, 0.0);

        final short[] C   = centroids;
        final short[] BMN = bboxMin;
        final short[] BMX = bboxMax;
        final int[]   OFF = offsets;
        final byte[]  LB  = labels;
        final int[]   OID = origIds;
        final int     k   = nClusters;

        int q0 =quantize(query[0]),  q1 =quantize(query[1]),
            q2 =quantize(query[2]),  q3 =quantize(query[3]),
            q4 =quantize(query[4]),  q5 =quantize(query[5]),
            q6 =quantize(query[6]),  q7 =quantize(query[7]),
            q8 =quantize(query[8]),  q9 =quantize(query[9]),
            q10=quantize(query[10]), q11=quantize(query[11]),
            q12=quantize(query[12]), q13=quantize(query[13]);

        Top5 t = THREAD_TOP5.get();
        t.reset();

        int seed = 0;
        long bestCentroid = Long.MAX_VALUE;
        for (int c = 0, cb = 0; c < k; c++, cb += DIM) {
            long d = centroidDist(C, cb, q0,q1,q2,q3,q4,q5,q6,q7,q8,q9,q10,q11,q12,q13);
            if (d < bestCentroid) { bestCentroid = d; seed = c; }
        }
        scanCluster(seed, R, LB, OID, OFF, t,
                    q0,q1,q2,q3,q4,q5,q6,q7,q8,q9,q10,q11,q12,q13);

        for (int c = 0, cb = 0; c < k; c++, cb += DIM) {
            if (c == seed) continue;
            long worst = t.d4;
            if (NOPRUNE || bboxLowerBound(BMN, BMX, cb, worst,
                               q0,q1,q2,q3,q4,q5,q6,q7,q8,q9,q10,q11,q12,q13) <= worst) {
                scanCluster(c, R, LB, OID, OFF, t,
                            q0,q1,q2,q3,q4,q5,q6,q7,q8,q9,q10,q11,q12,q13);
            }
        }

        int fc = t.f0 + t.f1 + t.f2 + t.f3 + t.f4;
        boolean approved = 5 * fc < 3 * K;
        return new FraudResult(approved, (double) fc / K);
    }

    private static long centroidDist(short[] C, int b,
            int q0,int q1,int q2,int q3,int q4,int q5,int q6,
            int q7,int q8,int q9,int q10,int q11,int q12,int q13) {
        int e0=q0-C[b],   e1=q1-C[b+1],  e2=q2-C[b+2],  e3=q3-C[b+3],
            e4=q4-C[b+4], e5=q5-C[b+5],  e6=q6-C[b+6],  e7=q7-C[b+7],
            e8=q8-C[b+8], e9=q9-C[b+9],  e10=q10-C[b+10],e11=q11-C[b+11],
            e12=q12-C[b+12], e13=q13-C[b+13];
        return (long)e0*e0 + (long)e1*e1 + (long)e2*e2  + (long)e3*e3
             + (long)e4*e4 + (long)e5*e5 + (long)e6*e6  + (long)e7*e7
             + (long)e8*e8 + (long)e9*e9 + (long)e10*e10+ (long)e11*e11
             + (long)e12*e12+ (long)e13*e13;
    }

    private static long bboxLowerBound(short[] BMN, short[] BMX, int b, long worst,
            int q0,int q1,int q2,int q3,int q4,int q5,int q6,
            int q7,int q8,int q9,int q10,int q11,int q12,int q13) {
        long s = 0;
        s = boxAxis(s, q0,  BMN[b],    BMX[b]);    if (s > worst) return s;
        s = boxAxis(s, q1,  BMN[b+1],  BMX[b+1]);  if (s > worst) return s;
        s = boxAxis(s, q2,  BMN[b+2],  BMX[b+2]);  if (s > worst) return s;
        s = boxAxis(s, q3,  BMN[b+3],  BMX[b+3]);  if (s > worst) return s;
        s = boxAxis(s, q4,  BMN[b+4],  BMX[b+4]);  if (s > worst) return s;
        s = boxAxis(s, q5,  BMN[b+5],  BMX[b+5]);  if (s > worst) return s;
        s = boxAxis(s, q6,  BMN[b+6],  BMX[b+6]);  if (s > worst) return s;
        s = boxAxis(s, q7,  BMN[b+7],  BMX[b+7]);  if (s > worst) return s;
        s = boxAxis(s, q8,  BMN[b+8],  BMX[b+8]);  if (s > worst) return s;
        s = boxAxis(s, q9,  BMN[b+9],  BMX[b+9]);  if (s > worst) return s;
        s = boxAxis(s, q10, BMN[b+10], BMX[b+10]); if (s > worst) return s;
        s = boxAxis(s, q11, BMN[b+11], BMX[b+11]); if (s > worst) return s;
        s = boxAxis(s, q12, BMN[b+12], BMX[b+12]); if (s > worst) return s;
        s = boxAxis(s, q13, BMN[b+13], BMX[b+13]);
        return s;
    }

    private static long boxAxis(long s, int q, int lo, int hi) {
        int delta = q < lo ? lo - q : (q > hi ? q - hi : 0);
        return s + (long) delta * delta;
    }

    private static void scanCluster(int c, short[] R, byte[] LB, int[] OID,
            int[] OFF, Top5 t,
            int q0,int q1,int q2,int q3,int q4,int q5,int q6,
            int q7,int q8,int q9,int q10,int q11,int q12,int q13) {
        final int from = OFF[c], to = OFF[c + 1];
        long worst = t.d4;
        for (int i = from, b = from * DIM; i < to; i++, b += DIM) {
            long d = (long)(q0 - R[b])   * (q0 - R[b]);                       if (d > worst) continue;
            int e=q1 -R[b+1];  d += (long)e*e;                               if (d > worst) continue;
            e=q2 -R[b+2];      d += (long)e*e;                               if (d > worst) continue;
            e=q3 -R[b+3];      d += (long)e*e;                               if (d > worst) continue;
            e=q4 -R[b+4];      d += (long)e*e;                               if (d > worst) continue;
            e=q5 -R[b+5];      d += (long)e*e;                               if (d > worst) continue;
            e=q6 -R[b+6];      d += (long)e*e;                               if (d > worst) continue;
            e=q7 -R[b+7];      d += (long)e*e;                               if (d > worst) continue;
            e=q8 -R[b+8];      d += (long)e*e;                               if (d > worst) continue;
            e=q9 -R[b+9];      d += (long)e*e;                               if (d > worst) continue;
            e=q10-R[b+10];     d += (long)e*e;                               if (d > worst) continue;
            e=q11-R[b+11];     d += (long)e*e;                               if (d > worst) continue;
            e=q12-R[b+12];     d += (long)e*e;                               if (d > worst) continue;
            e=q13-R[b+13];     d += (long)e*e;                               if (d > worst) continue;
            t.add(d, OID[i], LB[i]);
            worst = t.d4;
        }
    }

    private static final ThreadLocal<Top5> THREAD_TOP5 =
        ThreadLocal.withInitial(Top5::new);

    private static final class Top5 {
        long d0, d1, d2, d3, d4;
        int  o0, o1, o2, o3, o4;
        int  f0, f1, f2, f3, f4;

        void reset() {
            d0=d1=d2=d3=d4 = Long.MAX_VALUE;
            o0=o1=o2=o3=o4 = Integer.MAX_VALUE;
            f0=f1=f2=f3=f4 = 0;
        }

        void add(long d, int o, byte fr) {
            if (d == d4 && o >= o4) return;
            int f = fr;
            if (d < d3 || (d == d3 && o < o3)) {
                d4=d3; o4=o3; f4=f3;
                if (d < d2 || (d == d2 && o < o2)) {
                    d3=d2; o3=o2; f3=f2;
                    if (d < d1 || (d == d1 && o < o1)) {
                        d2=d1; o2=o1; f2=f1;
                        if (d < d0 || (d == d0 && o < o0)) {
                            d1=d0; o1=o0; f1=f0;
                            d0=d; o0=o; f0=f;
                        } else { d1=d; o1=o; f1=f; }
                    } else { d2=d; o2=o; f2=f; }
                } else { d3=d; o3=o; f3=f; }
            } else { d4=d; o4=o; f4=f; }
        }
    }

    public record FraudResult(boolean approved, double fraudScore) {}
}
