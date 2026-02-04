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

        // 1. PRONOSTICS IA
        cacheManager.registerCustomCache("pronosticsIA",
                Caffeine.newBuilder()
                        .maximumSize(500)
                        .expireAfterWrite(24, TimeUnit.HOURS)
                        .recordStats() // ✅ On active les stats partout
                        .build());

        // 3. STATISTIQUES (Le gros morceau)
        cacheManager.registerCustomCache("statsGlobales",
                Caffeine.newBuilder()
                        .maximumSize(10)
                        .expireAfterWrite(24, TimeUnit.HOURS)
                        .recordStats() // ✅ Ajouté (Supprime le warning)
                        .build());

        // 4. CONFIGURATION ALGO
        cacheManager.registerCustomCache("algoConfig",
                Caffeine.newBuilder()
                        .maximumSize(1)
                        .expireAfterWrite(1, TimeUnit.HOURS)
                        .recordStats() // ✅ Ajouté
                        .build());

        return cacheManager;
    }
}
