package com.dev.rinha_backend_2026;

import com.dev.rinha_backend_2026.config.NativeHints;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ImportRuntimeHints;

@ImportRuntimeHints(NativeHints.class)
@SpringBootApplication
public class RinhaBackend2026Application {

    static void main(String[] args) {
        SpringApplication.run(RinhaBackend2026Application.class, args);
    }

}
