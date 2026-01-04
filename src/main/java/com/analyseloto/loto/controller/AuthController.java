package com.analyseloto.loto.controller;

import com.analyseloto.loto.entity.ConfirmationToken;
import com.analyseloto.loto.entity.User;
import com.analyseloto.loto.repository.ConfirmationTokenRepository;
import com.analyseloto.loto.repository.UserRepository;
import com.analyseloto.loto.service.EmailService;
import com.analyseloto.loto.service.UserService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Controller
@RequiredArgsConstructor
public class AuthController {
    // Repositories
    private final UserRepository userRepository;
    private final ConfirmationTokenRepository tokenRepository;
    // Services
    private final EmailService emailService;
    private final UserService userService;

    /**
     * Valeur de l'URL de l'appli selon l'environnement
     */
    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

    /**
     * Affichage page login
     * @return
     */
    @GetMapping("/login")
    public String loginPage() { return "login"; }

    /**
     * Affichage page création compte
     * @return
     */
    @GetMapping("/register")
    public String registerPage() { return "register"; }

    /**
     * Action création nouvel utilisateur
     * @param email email
     * @param password mot de passe
     * @param firstName prénom
     * @param birthDate date de naissance
     * @param birthTime heure de naissance
     * @param birthCity ville de naissance
     * @param zodiacSign signe astrologique
     * @return
     */
    @PostMapping("/register")
    public String registerUser(@RequestParam String email,
                               @RequestParam String password,
                               @RequestParam(required = false) String firstName,
                               @RequestParam(required = false) LocalDate birthDate,
                               @RequestParam(required = false) String birthTime,
                               @RequestParam(required = false) String birthCity,
                               @RequestParam(required = false) String zodiacSign) {
        // On ne crée pas un compte à un email déjà existant
        if (userRepository.findByEmail(email).isPresent()) {
            return "redirect:/register?error";
        }

        // Création utilisateur
        User user = userService.createNewUser(email, password, firstName, birthDate, birthTime, birthCity, zodiacSign);
        // Enregistrement de l'utilisateur
        userRepository.save(user);

        // Création token
        ConfirmationToken confirmationToken = userService.createConfirmationTokenUser(user);
        // Enregistrement du token
        tokenRepository.save(confirmationToken);

        // Envoi Email avec le token
        String link = baseUrl + "/confirm?token=" + confirmationToken.getToken();
        emailService.sendConfirmationEmail(email, firstName, link);

        // Redirection vers login avec message spécial
        return "redirect:/login?mailSent";
    }

    /**
     * Action confirmation activation du compte (depuis email)
     * @param token token
     * @return
     */
    @GetMapping("/confirm")
    @Transactional
    public String confirmToken(@RequestParam("token") String token) {
        // Récupération du token pour savoir s'il existe ou non
        ConfirmationToken confirmToken = tokenRepository.findByToken(token)
                .orElse(null);

        if (confirmToken == null) {
            return "redirect:/login?tokenError"; // Token inconnu
        }

        if (confirmToken.getConfirmedAt() != null) {
            return "redirect:/login?alreadyConfirmed"; // Déjà cliqué
        }

        // Récupération date d'expiration
        LocalDateTime expiredAt = confirmToken.getExpiresAt();
        if (expiredAt.isBefore(LocalDateTime.now())) {
            return "redirect:/login?expired"; // Token expiré
        }

        // Validation réussie
        confirmToken.setConfirmedAt(LocalDateTime.now());

        // Activation de l'utilisateur
        User user = confirmToken.getUser();
        user.setEnabled(true);
        userRepository.save(user);

        return "redirect:/login?verified";
    }
}