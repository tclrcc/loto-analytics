package com.analyseloto.loto.config;

import com.analyseloto.loto.service.LotoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
@Slf4j
@RequiredArgsConstructor
public class AppStartupRunner {
    private final LotoService lotoService;

    @Async // Important : on ne bloque pas le démarrage du serveur
    @EventListener(ApplicationReadyEvent.class)
    public void warmupCache() {
        log.info("🔥 [WARMUP] Initialisation au démarrage...");
        // Appelle méthode vérification config algo
        lotoService.verificationAuDemarrage();
    }
}
