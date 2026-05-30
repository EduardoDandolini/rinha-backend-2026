package com.dev.rinha_backend_2026.tools;

import com.dev.rinha_backend_2026.service.KnnSearchService;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class IvfValidator {

    public static void main(String[] args) throws Exception {
        String binPath  = args.length > 0 ? args[0] : "/tmp/refs.bin";
        String testPath = args.length > 1 ? args[1] : "k6/test-data.json";

        KnnSearchService knn = new KnnSearchService();
        long t = System.currentTimeMillis();
        loadInto(knn, binPath);
        System.out.printf("[IvfValidator] index loaded (%,d refs) in %dms%n",
                knn.getRefCount(), System.currentTimeMillis() - t);

        List<ExactScorer.TestEntry> entries = ExactScorer.loadTestData(testPath);
        System.out.printf("[IvfValidator] %,d test entries%n", entries.size());

        AtomicInteger fp = new AtomicInteger(), fn = new AtomicInteger();
        entries.parallelStream().forEach(e -> {
            boolean got = knn.search(e.vector()).approved();
            if (got != e.expectedApproved()) {
                if (got) fn.incrementAndGet(); else fp.incrementAndGet();
            }
        });
        int fpV = fp.get(), fnV = fn.get(), E = fpV + fnV * 3;
        System.out.printf("[IvfValidator] FP=%d  FN=%d  E=%d%n", fpV, fnV, E);

        for (int i = 0; i < Math.min(2000, entries.size()); i++) knn.search(entries.get(i).vector());
        long t0 = System.nanoTime();
        AtomicLong checksum = new AtomicLong();
        for (ExactScorer.TestEntry e : entries) checksum.addAndGet(knn.search(e.vector()).approved() ? 1 : 0);
        long ns = System.nanoTime() - t0;
        System.out.printf("[IvfValidator] single-thread: %.3f ms/query over %,d queries (%.1fs total)%n",
                ns / 1e6 / entries.size(), entries.size(), ns / 1e9);
    }

    static void loadInto(KnnSearchService knn, String path) throws IOException {
        try (DataInputStream dis = new DataInputStream(
                new BufferedInputStream(new FileInputStream(path), 1 << 20))) {
            if (dis.readInt() != 0x52454653) throw new IllegalStateException("bad magic");
            int version = dis.readInt();
            if (version != 4) throw new IllegalStateException("expected v4, got v" + version);
            int n = dis.readInt(), k = dis.readInt();
            final int DIM = KnnSearchService.DIM;
            short[] centroids = readShorts(dis, k * DIM);
            short[] bboxMin   = readShorts(dis, k * DIM);
            short[] bboxMax   = readShorts(dis, k * DIM);
            int[]   offsets   = readInts(dis, k + 1);
            short[] rows      = readShorts(dis, n * DIM);
            byte[]  labels    = new byte[n];
            dis.readFully(labels);
            int[]   origIds   = readInts(dis, n);
            knn.setDataset(centroids, bboxMin, bboxMax, offsets, rows, labels, origIds, n, k);
        }
    }

    static short[] readShorts(DataInputStream dis, int count) throws IOException {
        short[] out = new short[count];
        byte[] buf = new byte[1 << 20];
        ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.BIG_ENDIAN);
        int off = 0, rem = count;
        while (rem > 0) { int c = Math.min(rem, buf.length / 2); dis.readFully(buf, 0, c * 2);
            bb.clear(); bb.asShortBuffer().get(out, off, c); off += c; rem -= c; }
        return out;
    }

    static int[] readInts(DataInputStream dis, int count) throws IOException {
        int[] out = new int[count];
        byte[] buf = new byte[1 << 20];
        ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.BIG_ENDIAN);
        int off = 0, rem = count;
        while (rem > 0) { int c = Math.min(rem, buf.length / 4); dis.readFully(buf, 0, c * 4);
            bb.clear(); bb.asIntBuffer().get(out, off, c); off += c; rem -= c; }
        return out;
    }
}
