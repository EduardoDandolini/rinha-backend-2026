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
        KnnSearchService.FraudResult result = KnnSearchService.evaluate(
                queryVec,
                knnSearchService.getVectors(),
                knnSearchService.getIsFraud(),
                knnSearchService.getSize()
        );
        return new FraudResponse(result.approved(), result.fraudScore());
    }
}
