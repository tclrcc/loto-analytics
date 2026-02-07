package com.analyseloto.loto.config;

import com.analyseloto.loto.service.LotoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

@Component
@Slf4j
@RequiredArgsConstructor
public class AppStartupRunner {
    private final LotoService lotoService;

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        // 1. D'abord on charge ce qu'on a en BDD (Synchrone et Prioritaire)
        lotoService.initConfigFromDb();

        // 2. Ensuite, on lance le calcul lourd si nécessaire (Délégué à un thread séparé)
        CompletableFuture.runAsync(() -> {
            log.info("🔥 [WARMUP] Vérification de la fraîcheur des données...");
            lotoService.verificationAuDemarrage();
        });
    }
}
