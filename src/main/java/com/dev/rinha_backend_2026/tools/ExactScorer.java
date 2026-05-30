package com.dev.rinha_backend_2026.tools;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.*;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.stream.IntStream;
import java.util.zip.GZIPInputStream;

public class ExactScorer {

    static final int   DIM       = 14;
    static final int   K         = 5;
    static final float THRESHOLD = 0.6f;

    static final double MAX_AMOUNT       = 10_000.0;
    static final double MAX_INSTALLMENTS =     12.0;
    static final double AMOUNT_VS_AVG    =     10.0;
    static final double MAX_MINUTES      =  1_440.0;
    static final double MAX_KM           =  1_000.0;
    static final double MAX_TX_24H       =     20.0;
    static final double MAX_MERCHANT_AVG = 10_000.0;

    static final Map<String, Float> MCC_RISK = Map.of(
            "5411", 0.15f, "5812", 0.30f, "5912", 0.20f, "5944", 0.45f,
            "7801", 0.80f, "7802", 0.75f, "7995", 0.85f, "4511", 0.35f,
            "5311", 0.25f, "5999", 0.50f
    );

    static float[]   refsF;
    static short[]   refsS;
    static byte[]    refsB;
    static boolean[] fraud;
    static int       nRefs;

    public static void main(String[] args) throws Exception {
        String testPath = args.length > 0 ? args[0] : "k6/test-data.json";

        System.out.println("[ExactScorer] Loading references.json.gz ...");
        long t = System.currentTimeMillis();
        loadReferences();
        System.out.printf("[ExactScorer] %,d references loaded in %dms%n",
                nRefs, System.currentTimeMillis() - t);

        System.out.println("[ExactScorer] Loading test data from " + testPath + " ...");
        t = System.currentTimeMillis();
        List<TestEntry> entries = loadTestData(testPath);
        System.out.printf("[ExactScorer] %,d test entries loaded in %dms%n",
                entries.size(), System.currentTimeMillis() - t);

        System.out.println();
        score("float32", entries, ExactScorer::approvedFloat);
        score("int16  ", entries, ExactScorer::approvedShort);
    }

    @FunctionalInterface
    interface Scorer { boolean apply(float[] query); }

    static void score(String label, List<TestEntry> entries, Scorer scorer) {
        long t = System.currentTimeMillis();
        AtomicInteger fp = new AtomicInteger(), fn = new AtomicInteger();

        entries.parallelStream().forEach(e -> {
            boolean got = scorer.apply(e.vector);
            if (got != e.expectedApproved) {
                if (got) fn.incrementAndGet();
                else     fp.incrementAndGet();
            }
        });

        int fpV = fp.get(), fnV = fn.get();
        int E   = fpV + fnV * 3;
        long ms = System.currentTimeMillis() - t;
        System.out.printf("[%s]  FP=%5d  FN=%5d  E=%5d  (%dms)%n",
                label, fpV, fnV, E, ms);
    }

    static boolean approvedFloat(float[] q) {
        float q0=q[0],q1=q[1],q2=q[2],q3=q[3],q4=q[4],q5=q[5],q6=q[6],
              q7=q[7],q8=q[8],q9=q[9],q10=q[10],q11=q[11],q12=q[12],q13=q[13];

        float t0=Float.MAX_VALUE,t1=Float.MAX_VALUE,t2=Float.MAX_VALUE,
              t3=Float.MAX_VALUE,t4=Float.MAX_VALUE;
        int   i0=-1,i1=-1,i2=-1,i3=-1,i4=-1;
        float worst = Float.MAX_VALUE;

        float[] R = refsF;
        for (int i=0, b=0; i<nRefs; i++, b+=DIM) {
            float d0=q0-R[b],d1=q1-R[b+1],d2=q2-R[b+2],d3=q3-R[b+3],
                  d4=q4-R[b+4],d5=q5-R[b+5],d6=q6-R[b+6],d7=q7-R[b+7],
                  d8=q8-R[b+8],d9=q9-R[b+9],d10=q10-R[b+10],d11=q11-R[b+11],
                  d12=q12-R[b+12],d13=q13-R[b+13];
            float d=d0*d0+d1*d1+d2*d2+d3*d3+d4*d4+d5*d5+d6*d6+
                    d7*d7+d8*d8+d9*d9+d10*d10+d11*d11+d12*d12+d13*d13;
            if (d < worst) {
                if      (t0==worst){t0=d;i0=i;}
                else if (t1==worst){t1=d;i1=i;}
                else if (t2==worst){t2=d;i2=i;}
                else if (t3==worst){t3=d;i3=i;}
                else               {t4=d;i4=i;}
                worst=Math.max(Math.max(Math.max(Math.max(t0,t1),t2),t3),t4);
            }
        }
        int fc=0;
        if(i0>=0&&fraud[i0])fc++; if(i1>=0&&fraud[i1])fc++;
        if(i2>=0&&fraud[i2])fc++; if(i3>=0&&fraud[i3])fc++;
        if(i4>=0&&fraud[i4])fc++;
        return (float)fc/K < THRESHOLD;
    }

    static boolean approvedShort(float[] q) {
        short[] Q = quantize16(q);
        short q0=Q[0],q1=Q[1],q2=Q[2],q3=Q[3],q4=Q[4],q5=Q[5],q6=Q[6],
              q7=Q[7],q8=Q[8],q9=Q[9],q10=Q[10],q11=Q[11],q12=Q[12],q13=Q[13];

        long d0=Long.MAX_VALUE,d1=Long.MAX_VALUE,d2=Long.MAX_VALUE,
             d3=Long.MAX_VALUE,d4=Long.MAX_VALUE;
        int  n0=Integer.MAX_VALUE,n1=Integer.MAX_VALUE,n2=Integer.MAX_VALUE,
             n3=Integer.MAX_VALUE,n4=Integer.MAX_VALUE;
        long worst = Long.MAX_VALUE;

        short[] R = refsS;
        for (int i=0, b=0; i<nRefs; i++, b+=DIM) {
            int e0=q0-R[b],e1=q1-R[b+1],e2=q2-R[b+2],e3=q3-R[b+3],
                e4=q4-R[b+4],e5=q5-R[b+5],e6=q6-R[b+6],e7=q7-R[b+7],
                e8=q8-R[b+8],e9=q9-R[b+9],e10=q10-R[b+10],e11=q11-R[b+11],
                e12=q12-R[b+12],e13=q13-R[b+13];
            long d=(long)e0*e0+(long)e1*e1+(long)e2*e2+(long)e3*e3+
                   (long)e4*e4+(long)e5*e5+(long)e6*e6+(long)e7*e7+
                   (long)e8*e8+(long)e9*e9+(long)e10*e10+(long)e11*e11+
                   (long)e12*e12+(long)e13*e13;
            if (d > worst || (d == worst && i >= n4)) continue;
            if (d < d3 || (d == d3 && i < n3)) {
                d4=d3; n4=n3;
                if (d < d2 || (d == d2 && i < n2)) {
                    d3=d2; n3=n2;
                    if (d < d1 || (d == d1 && i < n1)) {
                        d2=d1; n2=n1;
                        if (d < d0 || (d == d0 && i < n0)) {
                            d1=d0; n1=n0; d0=d; n0=i;
                        } else { d1=d; n1=i; }
                    } else { d2=d; n2=i; }
                } else { d3=d; n3=i; }
            } else { d4=d; n4=i; }
            worst = d4;
        }
        int fc=0;
        if(n0<nRefs&&fraud[n0])fc++; if(n1<nRefs&&fraud[n1])fc++;
        if(n2<nRefs&&fraud[n2])fc++; if(n3<nRefs&&fraud[n3])fc++;
        if(n4<nRefs&&fraud[n4])fc++;
        return (float)fc/K < THRESHOLD;
    }

    static boolean approvedByte(float[] q) {
        byte[] Q = quantize8(q);
        int q0=Q[0],q1=Q[1],q2=Q[2],q3=Q[3],q4=Q[4],q5=Q[5],q6=Q[6],
            q7=Q[7],q8=Q[8],q9=Q[9],q10=Q[10],q11=Q[11],q12=Q[12],q13=Q[13];

        int t0=Integer.MAX_VALUE,t1=Integer.MAX_VALUE,t2=Integer.MAX_VALUE,
            t3=Integer.MAX_VALUE,t4=Integer.MAX_VALUE;
        int i0=-1,i1=-1,i2=-1,i3=-1,i4=-1;
        int worst = Integer.MAX_VALUE;

        byte[] R = refsB;
        for (int i=0, b=0; i<nRefs; i++, b+=DIM) {
            int d0=q0-R[b],d1=q1-R[b+1],d2=q2-R[b+2],d3=q3-R[b+3],
                d4=q4-R[b+4],d5=q5-R[b+5],d6=q6-R[b+6],d7=q7-R[b+7],
                d8=q8-R[b+8],d9=q9-R[b+9],d10=q10-R[b+10],d11=q11-R[b+11],
                d12=q12-R[b+12],d13=q13-R[b+13];
            int d=d0*d0+d1*d1+d2*d2+d3*d3+d4*d4+d5*d5+d6*d6+
                  d7*d7+d8*d8+d9*d9+d10*d10+d11*d11+d12*d12+d13*d13;
            if (d < worst) {
                if      (t0==worst){t0=d;i0=i;}
                else if (t1==worst){t1=d;i1=i;}
                else if (t2==worst){t2=d;i2=i;}
                else if (t3==worst){t3=d;i3=i;}
                else               {t4=d;i4=i;}
                worst=Math.max(Math.max(Math.max(Math.max(t0,t1),t2),t3),t4);
            }
        }
        int fc=0;
        if(i0>=0&&fraud[i0])fc++; if(i1>=0&&fraud[i1])fc++;
        if(i2>=0&&fraud[i2])fc++; if(i3>=0&&fraud[i3])fc++;
        if(i4>=0&&fraud[i4])fc++;
        return (float)fc/K < THRESHOLD;
    }

    static final int SCALE16 = 10_000;

    static short quantize16(float x) {
        int q = Math.round(x * SCALE16);
        if (q >  SCALE16) return (short)  SCALE16;
        if (q < -SCALE16) return (short) -SCALE16;
        return (short) q;
    }

    static short[] quantize16(float[] v) {
        short[] s = new short[DIM];
        for (int i = 0; i < DIM; i++) s[i] = quantize16(v[i]);
        return s;
    }

    static byte[] quantize8(float[] v) {
        byte[] b = new byte[DIM];
        for (int i = 0; i < DIM; i++) {
            float x = v[i];
            b[i] = x <= -1f ? (byte) -128 : (byte) Math.round(x * 127f);
        }
        return b;
    }

    static void loadReferences() throws Exception {
        final int MAX = 3_100_000;
        refsF = new float[MAX * DIM];
        refsS = new short[MAX * DIM];
        refsB = new byte[MAX * DIM];
        fraud = new boolean[MAX];
        nRefs = 0;

        ObjectMapper mapper = new ObjectMapper();
        try (InputStream raw = ExactScorer.class.getResourceAsStream("/references.json.gz");
             InputStream gz   = new GZIPInputStream(raw, 1 << 16);
             JsonParser  jp   = mapper.createParser(gz)) {

            if (jp.nextToken() != JsonToken.START_ARRAY)
                throw new IllegalStateException("Expected START_ARRAY");

            float[] fvec = new float[DIM];

            while (jp.nextToken() != JsonToken.END_ARRAY) {
                boolean isFr = false;
                while (jp.nextToken() != JsonToken.END_OBJECT) {
                    String field = jp.currentName();
                    jp.nextToken();
                    if ("label".equals(field)) {
                        isFr = "fraud".equals(jp.getString());
                    } else if ("vector".equals(field)) {
                        int idx = 0;
                        while (jp.nextToken() != JsonToken.END_ARRAY)
                            fvec[idx++] = jp.getFloatValue();
                    }
                }
                int base = nRefs * DIM;
                for (int i = 0; i < DIM; i++) {
                    float x = fvec[i];
                    refsF[base + i] = x;
                    refsS[base + i] = quantize16(x);
                    refsB[base + i] = x <= -1f ? (byte) -128 : (byte) Math.round(x * 127f);
                }
                fraud[nRefs++] = isFr;
                if (nRefs % 500_000 == 0)
                    System.out.printf("  ... %,d references%n", nRefs);
            }
        }
    }

    record TestEntry(float[] vector, boolean expectedApproved) {}

    static List<TestEntry> loadTestData(String path) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root    = mapper.readTree(new File(path));
        JsonNode entries = root.get("entries");

        List<TestEntry> list = new ArrayList<>(entries.size());
        for (JsonNode e : entries) {
            boolean expected = e.get("expected_approved").booleanValue();
            float[] vec = vectorize(e.get("request"));
            list.add(new TestEntry(vec, expected));
        }
        return list;
    }

    static float[] vectorize(JsonNode req) {
        float[] v = new float[DIM];

        JsonNode tx       = req.get("transaction");
        JsonNode customer = req.get("customer");
        JsonNode merchant = req.get("merchant");
        JsonNode terminal = req.get("terminal");
        JsonNode lastTx   = req.get("last_transaction");

        double amount       = tx.get("amount").doubleValue();
        int    installments = tx.get("installments").intValue();
        String requestedAt  = tx.get("requested_at").textValue();

        double avgAmount  = customer.get("avg_amount").doubleValue();
        int    txCount24h = customer.get("tx_count_24h").intValue();

        String merchantId  = merchant.get("id").textValue();
        String mcc         = merchant.get("mcc").textValue();
        double merchantAvg = merchant.get("avg_amount").doubleValue();

        boolean isOnline    = terminal.get("is_online").booleanValue();
        boolean cardPresent = terminal.get("card_present").booleanValue();
        double  kmFromHome  = terminal.get("km_from_home").doubleValue();

        JsonNode km = customer.get("known_merchants");
        Set<String> knownMerchants = new HashSet<>();
        for (JsonNode n : km) knownMerchants.add(n.textValue());

        v[0] = clamp(amount / MAX_AMOUNT);
        v[1] = clamp(installments / MAX_INSTALLMENTS);
        v[2] = avgAmount == 0 ? 1f : clamp((amount / avgAmount) / AMOUNT_VS_AVG);

        ZonedDateTime dt = Instant.parse(requestedAt).atZone(ZoneOffset.UTC);
        v[3] = dt.getHour() / 23f;
        v[4] = (dt.getDayOfWeek().getValue() - 1) / 6f;

        if (lastTx == null || lastTx.isNull()) {
            v[5] = -1f;
            v[6] = -1f;
        } else {
            Instant cur  = Instant.parse(requestedAt);
            Instant last = Instant.parse(lastTx.get("timestamp").textValue());
            long minutes = Math.abs(ChronoUnit.MINUTES.between(last, cur));
            v[5] = clamp(minutes / MAX_MINUTES);
            v[6] = clamp(lastTx.get("km_from_current").doubleValue() / MAX_KM);
        }

        v[7]  = clamp(kmFromHome / MAX_KM);
        v[8]  = clamp(txCount24h / MAX_TX_24H);
        v[9]  = isOnline ? 1f : 0f;
        v[10] = cardPresent ? 1f : 0f;
        v[11] = knownMerchants.contains(merchantId) ? 0f : 1f;
        v[12] = MCC_RISK.getOrDefault(mcc, 0.5f);
        v[13] = clamp(merchantAvg / MAX_MERCHANT_AVG);

        return v;
    }

    static float clamp(double x) {
        return (float) Math.min(1.0, Math.max(0.0, x));
    }
}
