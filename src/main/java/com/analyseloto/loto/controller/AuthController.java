package com.analyseloto.loto.controller;

import com.analyseloto.loto.entity.ConfirmationToken;
import com.analyseloto.loto.entity.User;
import com.analyseloto.loto.repository.ConfirmationTokenRepository;
import com.analyseloto.loto.repository.UserRepository;
import com.analyseloto.loto.service.EmailService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
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

    private final UserRepository userRepository;
    private final ConfirmationTokenRepository tokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;

    @GetMapping("/login")
    public String loginPage() { return "login"; }

    @GetMapping("/register")
    public String registerPage() { return "register"; }

    // --- 1. INSCRIPTION AVEC ENVOI MAIL ---
    @PostMapping("/register")
    public String registerUser(@RequestParam String email,
                               @RequestParam String password,
                               @RequestParam String firstName,
                               @RequestParam LocalDate birthDate,
                               @RequestParam String birthTime,
                               @RequestParam String birthCity,
                               @RequestParam String zodiacSign) {

        if (userRepository.findByEmail(email).isPresent()) {
            return "redirect:/register?error";
        }

        // Création User (activé = false par défaut)
        User u = new User();
        u.setEmail(email);
        u.setPassword(passwordEncoder.encode(password));
        u.setFirstName(firstName);
        u.setBirthDate(birthDate);
        u.setBirthTime(birthTime);
        u.setBirthCity(birthCity);
        u.setZodiacSign(zodiacSign);
        u.setEnabled(false);
        u.setRole("USER");

        userRepository.save(u);

        // Création Token
        String token = UUID.randomUUID().toString();
        ConfirmationToken confirmationToken = new ConfirmationToken(
                token,
                LocalDateTime.now(),
                LocalDateTime.now().plusHours(24), // Expire dans 24h
                u
        );
        tokenRepository.save(confirmationToken);

        // Envoi Email (Adapter le lien avec votre domaine/port)
        String link = "http://localhost:8080/confirm?token=" + token;
        emailService.sendConfirmationEmail(email, firstName, link);

        // Redirection vers login avec message spécial
        return "redirect:/login?mailSent";
    }

    // --- 2. VALIDATION DU LIEN ---
    @GetMapping("/confirm")
    @Transactional // Pour s'assurer que les modifs en base sont atomiques
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