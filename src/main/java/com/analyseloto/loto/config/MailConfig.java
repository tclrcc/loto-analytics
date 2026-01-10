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
        JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
        mailSender.setHost("smtp.gmail.com");
        mailSender.setPort(587);

        mailSender.setUsername(username);
        mailSender.setPassword(password);

        Properties props = mailSender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.debug", "true");

        // --- LE CORRECTIF EST ICI ---
        // 1. On fait confiance à tout le monde (Gmail)
        props.put("mail.smtp.ssl.trust", "*");
        // 2. On désactive la vérification d'identité serveur
        props.put("mail.smtp.ssl.checkserveridentity", "false");
        // 3. LA CLEF DU PROBLEME : On force l'utilisation de la SocketFactory standard de Java
        // Cela empêche Angus Mail d'utiliser sa propre factory qui cherche "WINDOWS-ROOT"
        props.put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory");
        props.put("mail.smtp.socketFactory.fallback", "false");

        return mailSender;
    }

}
