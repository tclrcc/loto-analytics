package com.analyseloto.loto.config;

import lombok.extern.slf4j.Slf4j;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;



@Slf4j
@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate() {
        // 1. On configure une Factory standard pour gérer les timeouts
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();

        // Timeout de connexion (10 secondes)
        factory.setConnectTimeout(10000);
        // Timeout de lecture (10 secondes)
        factory.setReadTimeout(10000);

        // 2. On crée le RestTemplate avec cette factory.
        return new RestTemplate(factory);
    }
}
