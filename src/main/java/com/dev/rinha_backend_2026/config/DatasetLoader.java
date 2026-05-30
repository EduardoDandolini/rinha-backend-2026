package com.dev.rinha_backend_2026.config;

import com.dev.rinha_backend_2026.service.KnnSearchService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class DatasetLoader {

    private static final Logger log = LoggerFactory.getLogger(DatasetLoader.class);
    private static final String BIN_PATH = "/app/refs.bin";

    private final KnnSearchService knnSearchService;
    private final AtomicBoolean    ready = new AtomicBoolean(false);

    public DatasetLoader(KnnSearchService knnSearchService) {
        this.knnSearchService = knnSearchService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startLoading() {
        Thread.ofVirtual().name("dataset-loader").start(this::load);
    }

    private void load() {
        try {
            File f = new File(BIN_PATH);
            if (!f.exists()) throw new FileNotFoundException(BIN_PATH + " not found — rebuild image");
            loadBinary(f);
        } catch (Exception e) {
            log.error("Failed to load dataset", e);
        }
    }

    private void loadBinary(File f) throws IOException {
        log.info("Loading {} ({} MB)...", BIN_PATH, f.length() >> 20);
        long t0 = System.currentTimeMillis();

        try (DataInputStream dis = new DataInputStream(
                new BufferedInputStream(new FileInputStream(f), 1 << 20))) {

            int magic = dis.readInt();
            if (magic != 0x52454653)
                throw new IllegalStateException("Bad magic: 0x" + Integer.toHexString(magic));
            int version = dis.readInt();
            if (version != 4)
                throw new IllegalStateException("Expected v4, got v" + version + " — rebuild image");
            int n = dis.readInt();
            int k = dis.readInt();
            final int DIM = KnnSearchService.DIM;

            short[] centroids = readShorts(dis, k * DIM);
            short[] bboxMin   = readShorts(dis, k * DIM);
            short[] bboxMax   = readShorts(dis, k * DIM);
            int[]   offsets   = readInts(dis, k + 1);
            short[] rows      = readShorts(dis, n * DIM);
            byte[]  labels    = new byte[n];
            dis.readFully(labels);
            int[]   origIds   = readInts(dis, n);

            knnSearchService.setDataset(centroids, bboxMin, bboxMax, offsets,
                                        rows, labels, origIds, n, k);
            ready.set(true);
        }

        log.info("Index ready: {} refs loaded in {}ms",
                knnSearchService.getRefCount(), System.currentTimeMillis() - t0);
    }

    private static short[] readShorts(DataInputStream dis, int count) throws IOException {
        short[] out = new short[count];
        byte[]  buf = new byte[1 << 20];
        ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.BIG_ENDIAN);
        int offset = 0, remaining = count;
        while (remaining > 0) {
            int chunk = Math.min(remaining, buf.length / 2);
            dis.readFully(buf, 0, chunk * 2);
            bb.clear();
            bb.asShortBuffer().get(out, offset, chunk);
            offset += chunk; remaining -= chunk;
        }
        return out;
    }

    private static int[] readInts(DataInputStream dis, int count) throws IOException {
        int[] out = new int[count];
        byte[] buf = new byte[1 << 20];
        ByteBuffer bb = ByteBuffer.wrap(buf).order(ByteOrder.BIG_ENDIAN);
        int offset = 0, remaining = count;
        while (remaining > 0) {
            int chunk = Math.min(remaining, buf.length / 4);
            dis.readFully(buf, 0, chunk * 4);
            bb.clear();
            bb.asIntBuffer().get(out, offset, chunk);
            offset += chunk; remaining -= chunk;
        }
        return out;
    }

    public boolean isReady() { return ready.get(); }
}
