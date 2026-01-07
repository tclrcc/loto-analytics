package com.analyseloto.loto;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

@SpringBootApplication
@EnableScheduling
public class LotoApplication {

	public static void main(String[] args) {
        System.setProperty("javax.net.ssl.trustStoreType", "WINDOWS-ROOT");
		SpringApplication.run(LotoApplication.class, args);
	}

    /**
     * Méthode exécutée une seule fois au démarrage, force timezone à Paris
     */
    @PostConstruct
    public void init() {
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Paris"));
    }

}
