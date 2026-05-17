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
        float[] floatVec = vectorizationService.vectorize(request);
        byte[] queryVec = KnnSearchService.quantizeVector(floatVec);
        KnnSearchService.FraudResult result = knnSearchService.getHnsw().search(queryVec);
        return new FraudResponse(result.approved(), result.fraudScore());
    }
}
