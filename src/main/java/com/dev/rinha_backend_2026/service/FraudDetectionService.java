package com.dev.rinha_backend_2026.service;

import com.dev.rinha_backend_2026.domain.FraudResponse;
import com.dev.rinha_backend_2026.domain.TransactionRequest;
import org.springframework.stereotype.Service;

@Service
public class FraudDetectionService {

    private final VectorizationService vectorizationService;
    private final KnnSearchService knnSearchService;

    public FraudDetectionService(VectorizationService vectorizationService,
                                 KnnSearchService knnSearchService) {
        this.vectorizationService = vectorizationService;
        this.knnSearchService = knnSearchService;
    }

    public FraudResponse evaluate(TransactionRequest request) {
        float[] query = KnnSearchService.threadQuery();
        vectorizationService.vectorize(request, query);
        KnnSearchService.FraudResult result = knnSearchService.search(query);
        return new FraudResponse(result.approved(), result.fraudScore());
    }
}
