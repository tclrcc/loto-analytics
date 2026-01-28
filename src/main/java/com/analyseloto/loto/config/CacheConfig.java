package com.analyseloto.loto.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager();

        // 1. CACHE PRONOSTICS (IA & ASTRO)
        // Lourd à calculer, mais valable jusqu'au prochain tirage.
        // On garde 24h max ou jusqu'à éviction manuelle.
        cacheManager.registerCustomCache("pronosticsIA",
                Caffeine.newBuilder()
                        .maximumSize(500) // Max 500 listes de pronos en mémoire
                        .expireAfterWrite(24, TimeUnit.HOURS)
                        .recordStats() // Utile pour monitorer si besoin
                        .build());

        cacheManager.registerCustomCache("pronosticsAstro",
                Caffeine.newBuilder()
                        .maximumSize(500)
                        .expireAfterWrite(24, TimeUnit.HOURS)
                        .build());

        // 2. CACHE STATISTIQUES
        // Très lourd (analyse tout l'historique), mais ne change qu'après un tirage.
        cacheManager.registerCustomCache("statsGlobales",
                Caffeine.newBuilder()
                        .maximumSize(10) // On a peu de variantes (filtre par jour ou global)
                        .expireAfterWrite(24, TimeUnit.HOURS)
                        .build());

        // 3. CACHE CONFIGURATION ALGO
        // Petit objet, très fréquemment accédé (à chaque génération).
        // Accès quasi instantané requis.
        cacheManager.registerCustomCache("algoConfig",
                Caffeine.newBuilder()
                        .maximumSize(1) // Il n'y a qu'une config active
                        .expireAfterWrite(1, TimeUnit.HOURS) // Rafraichissement auto de sécurité
                        .build());

        return cacheManager;
    }
}
