package com.analyseloto.loto.config;

import com.analyseloto.loto.entity.User;
import com.analyseloto.loto.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {
    // Repositories
    private final UserRepository userRepository;
    // Utils
    private final PasswordEncoder passwordEncoder;
    /* Email de l'utilisateur ia */
    @Value("${user.ia.mail}")
    private String mailUserIa;

    @Override
    public void run(String... args) {
        log.info("🚀 Démarrage de l'initialisation des données...");
        // Création utilisateur IA
        creerUtilisateurIA();

        log.info("✅ Initialisation terminée.");
    }

    /**
     * Création d'un utilisateur IA pour les pronostics
     */
    private void creerUtilisateurIA() {
        // Vérification de la configuration de l'email
        if (mailUserIa == null || mailUserIa.isEmpty()) {
            log.warn("⚠️ L'email de l'utilisateur IA n'est pas configuré. Veuillez définir 'user.ia.mail' dans les propriétés.");
            return;
        }
        // On vérifie si l'utilisateur existe déjà pour ne pas créer de doublon
        if (userRepository.findByEmail(mailUserIa).isPresent()) {
            log.info("🤖 L'utilisateur IA existe déjà. Pas d'action requise.");
            return;
        }

        // Création de l'utilisateur s'il n'existe pas
        log.info("🤖 Création de l'utilisateur IA en cours...");

        User aiUser = new User();
        aiUser.setFirstName("Loto Master");
        aiUser.setEmail(mailUserIa);
        aiUser.setUsername("aiLoto");

        // Gestion du mot de passe (encodage)
        String passwordEncode = passwordEncoder.encode("admin");
         aiUser.setPassword(passwordEncode);

        // Autres champs obligatoires selon ton Entité
         aiUser.setRole("ADMIN");
         aiUser.setSystemAccount(true);
         aiUser.setSubscribeToEmails(false); // L'IA n'a pas besoin de mails
         aiUser.setBirthDate(LocalDate.now());

        userRepository.save(aiUser);
        log.info("✨ Utilisateur IA créé avec succès ! (Email: {})", mailUserIa);
    }
}
