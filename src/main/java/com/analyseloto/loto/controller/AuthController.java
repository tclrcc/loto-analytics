package com.analyseloto.loto.controller;

import com.analyseloto.loto.entity.ConfirmationToken;
import com.analyseloto.loto.entity.User;
import com.analyseloto.loto.repository.ConfirmationTokenRepository;
import com.analyseloto.loto.repository.UserRepository;
import com.analyseloto.loto.service.EmailService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Controller
@RequiredArgsConstructor
public class AuthController {
    // Repositories
    private final UserRepository userRepository;
    private final ConfirmationTokenRepository tokenRepository;
    // Services
    private final EmailService emailService;
    // Utils
    private final PasswordEncoder passwordEncoder;

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
                               @RequestParam String firstName,
                               @RequestParam LocalDate birthDate,
                               @RequestParam String birthTime,
                               @RequestParam String birthCity,
                               @RequestParam String zodiacSign) {
        // On ne crée pas un compte à un email déjà existant
        if (userRepository.findByEmail(email).isPresent()) {
            return "redirect:/register?error";
        }

        // Création User
        User u = new User();
        u.setEmail(email);
        // Cryptage du mot de passe
        u.setPassword(passwordEncoder.encode(password));
        u.setFirstName(firstName);
        u.setBirthDate(birthDate);
        u.setBirthTime(birthTime);
        u.setBirthCity(birthCity);
        u.setZodiacSign(zodiacSign);
        // Inactif par défaut
        u.setEnabled(false);
        // Rôle USER par défaut
        u.setRole("USER");

        // Enregistrement de l'utilisateur
        userRepository.save(u);

        // Création Token
        String token = UUID.randomUUID().toString();
        ConfirmationToken confirmationToken = new ConfirmationToken(
                token,
                LocalDateTime.now(),
                LocalDateTime.now().plusHours(24), // Expire dans 24h
                u
        );
        // Enregistrement du token de confirmation
        tokenRepository.save(confirmationToken);

        // Envoi Email (Adapter le lien avec votre domaine/port)
        String link = baseUrl + "/confirm?token=" + token;
        emailService.sendConfirmationEmail(email, firstName, link);

        // Redirection vers login avec message spécial
        return "redirect:/login?mailSent";
    }

    /**
     * Action confirmation activation du compte (depuis email)
     * @param token token
     * @param model model
     * @return
     */
    @GetMapping("/confirm")
    @Transactional
    public String confirmToken(@RequestParam("token") String token, Model model) {
        ConfirmationToken confirmToken = tokenRepository.findByToken(token)
                .orElse(null);

        if (confirmToken == null) {
            return "redirect:/login?tokenError"; // Token inconnu
        }

        if (confirmToken.getConfirmedAt() != null) {
            return "redirect:/login?alreadyConfirmed"; // Déjà cliqué
        }

        LocalDateTime expiredAt = confirmToken.getExpiresAt();
        if (expiredAt.isBefore(LocalDateTime.now())) {
            return "redirect:/login?expired"; // Trop tard
        }

        // Validation réussie
        confirmToken.setConfirmedAt(LocalDateTime.now());

        // On active l'utilisateur
        User user = confirmToken.getUser();
        user.setEnabled(true);
        userRepository.save(user);

        return "redirect:/login?verified";
    }
}