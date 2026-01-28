package com.analyseloto.loto.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import java.util.Properties;

@Configuration
public class MailConfig {

    @Value("${spring.mail.username}")
    private String username;

    @Value("${spring.mail.password}")
    private String password;

    @Bean
    public JavaMailSender javaMailSender() {
        // 1. On force Java à utiliser le format Linux (JKS)
        // Cela empêche l'erreur "WINDOWS-ROOT not available" à la racine
        System.setProperty("javax.net.ssl.trustStoreType", "JKS");

        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost("smtp.gmail.com");
        mailSender.setPort(587);

        mailSender.setUsername(username);
        mailSender.setPassword(password);

        Properties props = mailSender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");

        // 2. CORRECTIF SOCKET FACTORY (Version STARTTLS)
        // On interdit à Angus Mail d'utiliser sa propre factory (MailSSLSocketFactory) qui plante.
        // On force celle de Java standard.
        props.put("mail.smtp.ssl.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
        // On désactive le fallback pour être sûr qu'il ne retente pas sa méthode buggée
        props.put("mail.smtp.ssl.socketFactory.fallback", "false");

        // Sécurité TLS
        props.put("mail.smtp.ssl.protocols", "TLSv1.2");

        // Note : En utilisant la factory standard, on ne peut plus utiliser "ssl.trust = *".
        // Mais comme tu as installé les certificats dans le Dockerfile (ca-certificates-java),
        // Gmail sera reconnu officiellement.

        return mailSender;
    }
}
