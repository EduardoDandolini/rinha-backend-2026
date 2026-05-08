package com.dev.rinha_backend_2026.config;

import com.dev.rinha_backend_2026.service.KnnSearchService;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;

@Component
public class DatasetLoader {

    private static final Logger log = LoggerFactory.getLogger(DatasetLoader.class);
    private static final int INITIAL_CAPACITY = 3_000_000;

    private final KnnSearchService knnSearchService;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean ready = new AtomicBoolean(false);

    public DatasetLoader(KnnSearchService knnSearchService, ObjectMapper objectMapper) {
        this.knnSearchService = knnSearchService;
        this.objectMapper = objectMapper;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void startLoading() {
        Thread.ofVirtual().name("dataset-loader").start(this::load);
    }

    private void load() {
        try {
            log.info("Loading dataset from classpath:references.json.gz ...");
            long start = System.currentTimeMillis();

            byte[] vectors = new byte[INITIAL_CAPACITY * KnnSearchService.DIM];
            boolean[] isFraud = new boolean[INITIAL_CAPACITY];
            int count = 0;

            try (InputStream raw = getClass().getResourceAsStream("/references.json.gz");
                 InputStream in = new GZIPInputStream(raw, 65536);
                 JsonParser parser = objectMapper.createParser(in)) {

                if (parser.nextToken() != JsonToken.START_ARRAY) {
                    throw new IllegalStateException("Expected START_ARRAY at root");
                }

                byte[] vec = new byte[KnnSearchService.DIM];

                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    boolean fraud = false;

                    while (parser.nextToken() != JsonToken.END_OBJECT) {
                        String field = parser.currentName();
                        parser.nextToken();

                        if ("label".equals(field)) {
                            fraud = "fraud".equals(parser.getString());
                        } else if ("vector".equals(field)) {
                            int idx = 0;
                            while (parser.nextToken() != JsonToken.END_ARRAY) {
                                vec[idx++] = KnnSearchService.quantize(parser.getFloatValue());
                            }
                        }
                    }

                    System.arraycopy(vec, 0, vectors, count * KnnSearchService.DIM, KnnSearchService.DIM);
                    isFraud[count] = fraud;
                    count++;

                    if (count % 500_000 == 0) {
                        log.info("  ... {} vectors loaded", count);
                    }
                }
            }

            knnSearchService.init(vectors, isFraud, count);
            ready.set(true);

            log.info("Dataset ready: {} vectors in {}ms", count, System.currentTimeMillis() - start);

        } catch (Exception e) {
            log.error("Failed to load dataset", e);
        }
    }

    public boolean isReady() {
        return ready.get();
    }
}
