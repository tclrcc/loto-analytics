package com.analyseloto.loto.service;

import com.analyseloto.loto.entity.ConfirmationToken;
import com.analyseloto.loto.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {
    // Utils
    private final PasswordEncoder passwordEncoder;

    /**
     * Création d'un nouvel utilisateur
     * @param email email
     * @param password mot de passe
     * @param firstName prénom
     * @param birthDate date de naissance
     * @param birthTime heure de naissance
     * @param birthCity lieu de naissance
     * @param zodiacSign signe astrologique
     * @return utilisateur
     */
    public User createNewUser(String email, String password, String firstName, LocalDate birthDate,
                              String birthTime, String birthCity, String zodiacSign) {
        User user = new User();
        user.setEmail(email);
        // Cryptage du mot de passe
        user.setPassword(passwordEncoder.encode(password));
        // Inactif par défaut
        user.setEnabled(false);
        // Rôle USER par défaut
        user.setRole("USER");

        user.setFirstName((firstName != null && !firstName.isEmpty()) ? firstName : "Joueur");
        if (birthDate != null) {
            user.setBirthDate(birthDate);
        }
        if (birthTime != null && !birthTime.isEmpty()) {
            user.setBirthTime(birthTime);
        }
        user.setBirthCity(birthCity);
        user.setZodiacSign(zodiacSign);

        return user;
    }

    /**
     * Création du token de confirmation envoyé par mail pour activer le compte
     * @param user utilisateur
     * @return ConfirmationToken
     */
    public ConfirmationToken createConfirmationTokenUser(User user) {
        // Génération du token (aléatoire)
        String token = UUID.randomUUID().toString();

        return new ConfirmationToken(
                token,
                LocalDateTime.now(),
                LocalDateTime.now().plusHours(24), // Expire dans 24h
                user
        );
    }
}
