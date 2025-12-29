package com.analyseloto.loto;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class LotoApplication {

	public static void main(String[] args) {
		SpringApplication.run(LotoApplication.class, args);
	}

}
