package com.analyseloto.loto;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class LotoApplicationTests {

	@Test
	void contextLoads() {
		// Ce test vérifie simplement que l'application (et donc le cache Caffeine) démarre correctement.
	}

}
