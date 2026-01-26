package com.analyseloto.loto.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.HttpClient;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.client5.http.ssl.NoopHostnameVerifier;
import org.apache.hc.core5.util.Timeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

@Slf4j
@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate() {
        try {
            // 1. On crée un contexte SSL de la manière la plus brute possible
            // pour éviter que Java ne cherche le magasin "WINDOWS-ROOT".
            SSLContext sslContext = SSLContext.getInstance("TLS");

            // On initialise avec un TrustManager qui accepte tout sans regarder le magasin système
            sslContext.init(null, new TrustManager[]{
                    new X509TrustManager() {
                        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                        public void checkClientTrusted(X509Certificate[] certs, String authType) {}
                        public void checkServerTrusted(X509Certificate[] certs, String authType) {}
                    }
            }, new SecureRandom());

            // 2. Configuration de la connexion (Niveau Socket)
            // C'est ici que sont déplacés les timeouts de connexion
            ConnectionConfig connectionConfig = ConnectionConfig.custom()
                    .setConnectTimeout(Timeout.ofSeconds(5))
                    .setSocketTimeout(Timeout.ofSeconds(5))
                    .build();

            // 3. Configuration de la requête (Niveau applicatif)
            RequestConfig requestConfig = RequestConfig.custom()
                    .setResponseTimeout(Timeout.ofSeconds(5))
                    .setConnectionRequestTimeout(Timeout.ofSeconds(5))
                    .build();

            // 4. Assemblage avec la nouvelle TlsStrategy
            var tlsStrategy = new DefaultClientTlsStrategy(sslContext, NoopHostnameVerifier.INSTANCE);

            var connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                    .setTlsSocketStrategy(tlsStrategy)
                    .setDefaultConnectionConfig(connectionConfig)
                    .build();

            HttpClient httpClient = HttpClients.custom()
                    .setConnectionManager(connectionManager)
                    .setDefaultRequestConfig(requestConfig)
                    .build();

            return new RestTemplate(new HttpComponentsClientHttpRequestFactory(httpClient));
        } catch (Exception e) {
            // Au cas où ça échoue encore, on renvoie un RestTemplate basique pour ne pas bloquer le démarrage
            log.error("❌ Impossible de configurer le SSL personnalisé, retour au mode dégradé", e);
            return new RestTemplate();
        }
    }
}
