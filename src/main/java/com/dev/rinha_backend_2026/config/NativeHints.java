package com.dev.rinha_backend_2026.config;

import com.dev.rinha_backend_2026.domain.FraudResponse;
import com.dev.rinha_backend_2026.domain.TransactionRequest;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

public class NativeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        hints.resources().registerPattern("references.json.gz");
        hints.resources().registerPattern("normalization.json");
        hints.resources().registerPattern("mcc_risk.json");

        hints.reflection().registerType(NormalizationConfig.class,
                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
        hints.reflection().registerType(TransactionRequest.class,
                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
        hints.reflection().registerType(TransactionRequest.Transaction.class,
                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
        hints.reflection().registerType(TransactionRequest.Customer.class,
                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
        hints.reflection().registerType(TransactionRequest.Merchant.class,
                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
        hints.reflection().registerType(TransactionRequest.Terminal.class,
                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
        hints.reflection().registerType(TransactionRequest.LastTransaction.class,
                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
        hints.reflection().registerType(FraudResponse.class,
                MemberCategory.INVOKE_DECLARED_CONSTRUCTORS);
    }
}
