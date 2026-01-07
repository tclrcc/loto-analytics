package com.analyseloto.loto.service;

import com.analyseloto.loto.dto.UserRegistrationDto;
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
     * @param dto données du formulaire d'inscription
     * @return utilisateur
     */
    public User createNewUser(UserRegistrationDto dto) {
        User user = new User();
        user.setUsername(dto.getUsername());
        user.setEmail(dto.getEmail());
        // Cryptage du mot de passe
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        // Inactif par défaut
        user.setEnabled(false);
        // Rôle USER par défaut
        user.setRole("USER");

        String firstName = dto.getFirstName();
        user.setFirstName((firstName != null && !firstName.isEmpty()) ? firstName : "Joueur");
        LocalDate birthDate = dto.getBirthDate();
        if (birthDate != null) {
            user.setBirthDate(birthDate);
        }
        String birthTime = dto.getBirthTime();
        if (birthTime != null && !birthTime.isEmpty()) {
            user.setBirthTime(birthTime);
        }
        user.setBirthCity(dto.getBirthCity());
        user.setZodiacSign(dto.getZodiacSign());

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
