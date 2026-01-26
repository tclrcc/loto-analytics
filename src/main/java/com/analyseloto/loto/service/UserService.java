package com.analyseloto.loto.service;

import com.analyseloto.loto.dto.UserRegistrationDto;
import com.analyseloto.loto.entity.ConfirmationToken;
import com.analyseloto.loto.entity.User;
import com.analyseloto.loto.enums.RoleUser;
import com.analyseloto.loto.repository.UserRepository;
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
    // Repositories
    private final UserRepository userRepository;
    // Services
    private final UserBilanService userBilanService;
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
        user.setRole(RoleUser.USER.name());

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
     * Méthode de création de l'utilisateur IA ainsi que son bilan
     * @param mailUserIa mail user IA
     */
    public void createUserIaAndBilan(String mailUserIa) {
        log.info("🤖 Création de l'utilisateur IA en cours...");

        User aiUser = new User();
        aiUser.setFirstName("Loto Master");
        aiUser.setEmail(mailUserIa);
        aiUser.setUsername("aiLoto");

        // Gestion du mot de passe (encodage)
        String passwordEncode = passwordEncoder.encode("admin");
        aiUser.setPassword(passwordEncode);

        // Autres champs obligatoires selon ton Entité
        aiUser.setRole(RoleUser.ADMIN.name());
        aiUser.setSystemAccount(true);
        aiUser.setSubscribeToEmails(false); // L'IA n'a pas besoin de mails
        aiUser.setBirthDate(LocalDate.now());

        aiUser = userRepository.save(aiUser);
        log.info("✨ Utilisateur IA créé avec succès ! (Email: {})", mailUserIa);

        // Création du bilan de l'utilisateur
        userBilanService.initializeBilanUser(aiUser);
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
