package com.analyseloto.loto;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class LotoApplicationTests {

	@TestConfiguration
	static class CacheTestConfig {
		@Bean
		public CacheManager cacheManager() {
			return new ConcurrentMapCacheManager(); // Un vrai cache basique en RAM
		}
	}

	@Test
	void contextLoads() {
		// Si le test arrive ici, c'est que Spring a réussi à démarrer !
	}

}
