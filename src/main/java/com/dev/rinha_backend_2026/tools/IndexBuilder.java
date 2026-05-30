package com.dev.rinha_backend_2026.tools;

import com.dev.rinha_backend_2026.service.KnnSearchService;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.ObjectMapper;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;
import java.util.zip.GZIPInputStream;
import java.util.stream.IntStream;

public class IndexBuilder {

    static final int  MAGIC       = 0x52454653;
    static final int  VERSION     = 4;
    static final int  DIM         = KnnSearchService.DIM;
    static final int  K           = 2048;
    static final int  MAX_CLUSTER = 1024;
    static final int  ITERS       = 12;
    static final int  SAMPLE      = 200_000;
    static final long SEED        = 0x9E3779B97F4A7C15L;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) { System.err.println("Usage: IndexBuilder <output>"); System.exit(1); }
        String outPath = args[0];

        long t0 = System.currentTimeMillis();
        System.out.println("[IndexBuilder] Reading references.json.gz (full dataset) ...");

        final int MAX = 3_100_000;
        short[] flat   = new short[MAX * DIM];
        byte[]  labels = new byte[MAX];
        int n = 0;

        ObjectMapper mapper = new ObjectMapper();
        try (InputStream raw = IndexBuilder.class.getResourceAsStream("/references.json.gz");
             InputStream gz  = new GZIPInputStream(raw, 1 << 16);
             JsonParser  jp  = mapper.createParser(gz)) {

            if (jp.nextToken() != JsonToken.START_ARRAY)
                throw new IllegalStateException("Expected START_ARRAY");

            while (jp.nextToken() != JsonToken.END_ARRAY) {
                boolean fraud = false;
                int base = n * DIM;
                while (jp.nextToken() != JsonToken.END_OBJECT) {
                    String field = jp.currentName();
                    jp.nextToken();
                    if ("label".equals(field)) {
                        fraud = "fraud".equals(jp.getString());
                    } else if ("vector".equals(field)) {
                        int idx = 0;
                        while (jp.nextToken() != JsonToken.END_ARRAY)
                            flat[base + idx++] = KnnSearchService.quantize(jp.getFloatValue());
                    }
                }
                labels[n++] = fraud ? (byte) 1 : 0;
                if (n % 500_000 == 0) System.out.printf("[IndexBuilder] %,d read%n", n);
            }
        }
        System.out.printf("[IndexBuilder] %,d refs read in %dms%n", n, System.currentTimeMillis() - t0);

        long tk = System.currentTimeMillis();
        Random rng = new Random(SEED);
        short[] centroids = kmeans(sample(flat, n, SAMPLE, rng), Math.min(K, n), rng);
        int k = centroids.length / DIM;
        System.out.printf("[IndexBuilder] k-means K=%d done in %dms%n", k, System.currentTimeMillis() - tk);

        int[] coarse = assignAll(flat, n, centroids, k);

        int[][] inverted = invert(coarse, n, k);
        int[] finalAssign = new int[n];
        int kFinal = 0;
        int split = 0;
        for (int c = 0; c < k; c++) {
            int[] members = inverted[c];
            if (members.length <= MAX_CLUSTER) {
                int id = kFinal++;
                for (int p : members) finalAssign[p] = id;
            } else {
                split++;
                short[] sub = gather(flat, members);
                int subK = (members.length + MAX_CLUSTER - 1) / MAX_CLUSTER;
                short[] subCentroids = kmeans(sub, subK, new Random(SEED + c + 1));
                int subKActual = subCentroids.length / DIM;
                int[] subAssign = assignAll(sub, members.length, subCentroids, subKActual);
                int[] subIds = new int[subKActual];
                for (int s = 0; s < subKActual; s++) subIds[s] = kFinal++;
                for (int j = 0; j < members.length; j++)
                    finalAssign[members[j]] = subIds[subAssign[j]];
            }
        }
        System.out.printf("[IndexBuilder] split: %d coarse -> %d final clusters (%d split)%n",
                k, kFinal, split);

        int[] offsets = new int[kFinal + 1];
        for (int i = 0; i < n; i++) offsets[finalAssign[i] + 1]++;
        for (int c = 0; c < kFinal; c++) offsets[c + 1] += offsets[c];

        short[] rows    = new short[n * DIM];
        byte[]  outLab  = new byte[n];
        int[]   origIds = new int[n];
        short[] cOut    = new short[kFinal * DIM];
        short[] bMin    = new short[kFinal * DIM];
        short[] bMax    = new short[kFinal * DIM];
        long[]  acc     = new long[kFinal * DIM];
        for (int c = 0; c < kFinal; c++) {
            int cb = c * DIM;
            for (int d = 0; d < DIM; d++) { bMin[cb + d] = Short.MAX_VALUE; bMax[cb + d] = Short.MIN_VALUE; }
        }

        int[] cursor = new int[kFinal];
        for (int c = 0; c < kFinal; c++) cursor[c] = offsets[c];
        for (int i = 0; i < n; i++) {
            int c = finalAssign[i];
            int pos = cursor[c]++;
            int src = i * DIM, dst = pos * DIM, cb = c * DIM;
            for (int d = 0; d < DIM; d++) {
                short v = flat[src + d];
                rows[dst + d] = v;
                if (v < bMin[cb + d]) bMin[cb + d] = v;
                if (v > bMax[cb + d]) bMax[cb + d] = v;
                acc[cb + d] += v;
            }
            outLab[pos]  = labels[i];
            origIds[pos] = i;
        }
        for (int c = 0; c < kFinal; c++) {
            int cb = c * DIM, cnt = offsets[c + 1] - offsets[c];
            if (cnt == 0) continue;
            for (int d = 0; d < DIM; d++) cOut[cb + d] = (short) Math.round((double) acc[cb + d] / cnt);
        }

        System.out.printf("[IndexBuilder] Writing %s ...%n", outPath);
        long tw = System.currentTimeMillis();
        try (DataOutputStream dos = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(outPath), 1 << 23))) {
            dos.writeInt(MAGIC);
            dos.writeInt(VERSION);
            dos.writeInt(n);
            dos.writeInt(kFinal);
            writeShorts(dos, cOut);
            writeShorts(dos, bMin);
            writeShorts(dos, bMax);
            writeInts(dos, offsets);
            writeShorts(dos, rows);
            dos.write(outLab);
            writeInts(dos, origIds);
        }
        long fileSize = new File(outPath).length();
        System.out.printf("[IndexBuilder] refs.bin: %,d bytes (%.1f MB) in %dms (total %dms)%n",
                fileSize, fileSize / 1048576.0, System.currentTimeMillis() - tw,
                System.currentTimeMillis() - t0);
    }

    static short[] kmeans(short[] data, int k, Random rng) {
        int m = data.length / DIM;
        k = Math.min(k, m);
        short[] centroids = kmeansPlusPlus(data, m, k, rng);
        int[] assign = new int[m];
        for (int iter = 0; iter < ITERS; iter++) {
            assignInto(data, m, centroids, k, assign);
            long[] acc = new long[k * DIM];
            int[]  cnt = new int[k];
            for (int i = 0; i < m; i++) {
                int c = assign[i], src = i * DIM, cb = c * DIM;
                cnt[c]++;
                for (int d = 0; d < DIM; d++) acc[cb + d] += data[src + d];
            }
            for (int c = 0; c < k; c++) {
                if (cnt[c] == 0) continue;
                int cb = c * DIM;
                for (int d = 0; d < DIM; d++)
                    centroids[cb + d] = (short) Math.round((double) acc[cb + d] / cnt[c]);
            }
        }
        return centroids;
    }

    static short[] kmeansPlusPlus(short[] data, int m, int k, Random rng) {
        short[] centroids = new short[k * DIM];
        long[]  dist = new long[m];
        int first = rng.nextInt(m);
        System.arraycopy(data, first * DIM, centroids, 0, DIM);
        for (int i = 0; i < m; i++) dist[i] = sqDist(data, i * DIM, centroids, 0);
        for (int c = 1; c < k; c++) {
            double total = 0;
            for (long dd : dist) total += dd;
            double threshold = rng.nextDouble() * total, cum = 0;
            int chosen = m - 1;
            for (int i = 0; i < m; i++) { cum += dist[i]; if (cum >= threshold) { chosen = i; break; } }
            int cb = c * DIM;
            System.arraycopy(data, chosen * DIM, centroids, cb, DIM);
            for (int i = 0; i < m; i++) {
                long d = sqDist(data, i * DIM, centroids, cb);
                if (d < dist[i]) dist[i] = d;
            }
        }
        return centroids;
    }

    static void assignInto(short[] data, int m, short[] centroids, int k, int[] out) {
        IntStream.range(0, m).parallel().forEach(i -> out[i] = nearest(data, i * DIM, centroids, k));
    }

    static int[] assignAll(short[] data, int m, short[] centroids, int k) {
        int[] out = new int[m];
        assignInto(data, m, centroids, k, out);
        return out;
    }

    static int nearest(short[] data, int off, short[] centroids, int k) {
        int best = 0;
        long bestD = Long.MAX_VALUE;
        for (int c = 0; c < k; c++) {
            long d = sqDist(data, off, centroids, c * DIM);
            if (d < bestD) { bestD = d; best = c; }
        }
        return best;
    }

    static long sqDist(short[] a, int ao, short[] b, int bo) {
        long s = 0;
        for (int d = 0; d < DIM; d++) {
            int e = a[ao + d] - b[bo + d];
            s += (long) e * e;
        }
        return s;
    }

    static short[] sample(short[] flat, int n, int s, Random rng) {
        if (s >= n) return java.util.Arrays.copyOf(flat, n * DIM);
        short[] out = new short[s * DIM];
        for (int i = 0; i < s; i++) {
            int src = rng.nextInt(n) * DIM;
            System.arraycopy(flat, src, out, i * DIM, DIM);
        }
        return out;
    }

    static int[][] invert(int[] assign, int n, int k) {
        int[] sizes = new int[k];
        for (int i = 0; i < n; i++) sizes[assign[i]]++;
        int[][] out = new int[k][];
        for (int c = 0; c < k; c++) out[c] = new int[sizes[c]];
        int[] pos = new int[k];
        for (int i = 0; i < n; i++) { int c = assign[i]; out[c][pos[c]++] = i; }
        return out;
    }

    static short[] gather(short[] flat, int[] ids) {
        short[] out = new short[ids.length * DIM];
        for (int j = 0; j < ids.length; j++)
            System.arraycopy(flat, ids[j] * DIM, out, j * DIM, DIM);
        return out;
    }

    static void writeShorts(DataOutputStream dos, short[] arr) throws IOException {
        byte[] buf = new byte[1 << 22];
        ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.BIG_ENDIAN);
        int remaining = arr.length, offset = 0;
        while (remaining > 0) {
            int chunk = Math.min(remaining, buf.length / 2);
            bb.clear();
            bb.asShortBuffer().put(arr, offset, chunk);
            dos.write(buf, 0, chunk * 2);
            offset += chunk; remaining -= chunk;
        }
    }

    static void writeInts(DataOutputStream dos, int[] arr) throws IOException {
        byte[] buf = new byte[1 << 22];
        ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.BIG_ENDIAN);
        int remaining = arr.length, offset = 0;
        while (remaining > 0) {
            int chunk = Math.min(remaining, buf.length / 4);
            bb.clear();
            bb.asIntBuffer().put(arr, offset, chunk);
            dos.write(buf, 0, chunk * 4);
            offset += chunk; remaining -= chunk;
        }
    }
}
