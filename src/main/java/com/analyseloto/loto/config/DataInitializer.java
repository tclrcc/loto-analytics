package com.analyseloto.loto.config;

import com.analyseloto.loto.entity.User;
import com.analyseloto.loto.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {
    private final UserRepository userRepository;

    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) throws Exception {
        log.info("🚀 Démarrage de l'initialisation des données...");
        creerUtilisateurIA();
        log.info("✅ Initialisation terminée.");
    }

    private void creerUtilisateurIA() {
        String emailIA = "ai@loto.com";

        // 1. On vérifie si l'utilisateur existe déjà pour ne pas créer de doublon
        if (userRepository.findByEmail(emailIA).isPresent()) {
            log.info("🤖 L'utilisateur IA existe déjà. Pas d'action requise.");
            return;
        }

        // 2. Création de l'utilisateur s'il n'existe pas
        log.info("🤖 Création de l'utilisateur IA en cours...");

        User aiUser = new User();
        aiUser.setFirstName("Loto Master");
        aiUser.setEmail(emailIA);

        // Gestion du mot de passe
         aiUser.setPassword(passwordEncoder.encode("admin"));

        // Autres champs obligatoires selon ton Entité
         aiUser.setRole("ADMIN");
         aiUser.setSubscribeToEmails(false); // L'IA n'a pas besoin de mails
         aiUser.setBirthDate(LocalDate.now());

        userRepository.save(aiUser);
        log.info("✨ Utilisateur IA créé avec succès ! (Email: {})", emailIA);
    }
}
