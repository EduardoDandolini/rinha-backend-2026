package com.dev.rinha_backend_2026.web;

import com.dev.rinha_backend_2026.config.DatasetLoader;
import com.dev.rinha_backend_2026.domain.FraudResponse;
import com.dev.rinha_backend_2026.domain.TransactionRequest;
import com.dev.rinha_backend_2026.service.FraudDetectionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class FraudController {

    private final FraudDetectionService fraudDetectionService;
    private final DatasetLoader datasetLoader;

    public FraudController(FraudDetectionService fraudDetectionService,
                           DatasetLoader datasetLoader) {
        this.fraudDetectionService = fraudDetectionService;
        this.datasetLoader = datasetLoader;
    }

    @GetMapping("/ready")
    public ResponseEntity<Void> ready() {
        return datasetLoader.isReady()
                ? ResponseEntity.ok().build()
                : ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }

    @PostMapping("/fraud-score")
    public ResponseEntity<FraudResponse> fraudScore(@RequestBody TransactionRequest request) {
        if (!datasetLoader.isReady()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        return ResponseEntity.ok(fraudDetectionService.evaluate(request));
    }
}
