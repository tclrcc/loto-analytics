package com.analyseloto.loto.config;

import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactoryBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import javax.net.ssl.SSLContext;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

@Configuration
public class RestTemplateConfig {
    @Bean
    public RestTemplate restTemplate() throws NoSuchAlgorithmException, KeyManagementException {
        // C'EST ICI LA CLÉ DU SUCCÈS :
        // On ne demande pas "Default" (qui plante), on demande explicitement "TLS"
        // Java va créer un contexte frais en mémoire au lieu de lire la config système cassée.
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, null, new SecureRandom());

        // On injecte ce contexte sain dans le client Apache
        HttpClient httpClient = HttpClients.custom()
                .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                        .setSSLSocketFactory(SSLConnectionSocketFactoryBuilder.create()
                                .setSslContext(sslContext)
                                .build())
                        .build())
                .build();

        // On dit à Spring d'utiliser ce client là
        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory(httpClient);
        // Timeouts de sécurité
        factory.setReadTimeout(5000);
        factory.setConnectionRequestTimeout(5000);

        return new RestTemplate(factory);
    }
}
