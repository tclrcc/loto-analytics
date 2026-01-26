package com.analyseloto.loto.controller;

import com.analyseloto.loto.dto.UserRegistrationDto;
import com.analyseloto.loto.entity.ConfirmationToken;
import com.analyseloto.loto.entity.User;
import com.analyseloto.loto.repository.ConfirmationTokenRepository;
import com.analyseloto.loto.repository.UserRepository;
import com.analyseloto.loto.service.ConfirmationTokenService;
import com.analyseloto.loto.service.EmailService;
import com.analyseloto.loto.service.UserBilanService;
import com.analyseloto.loto.service.UserService;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.LocalDateTime;
import java.util.Objects;

@Controller
@RequiredArgsConstructor
public class AuthController {
    // Repositories
    private final UserRepository userRepository;
    private final ConfirmationTokenRepository tokenRepository;
    // Services
    private final EmailService emailService;
    private final UserService userService;
    private final UserBilanService userBilanService;
    private final ConfirmationTokenService confirmationTokenService;

    /**
     * Valeur de l'URL de l'appli selon l'environnement
     */
    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

    /**
     * Affichage page login
     * @return page
     */
    @GetMapping("/login")
    public String loginPage() { return "login"; }

    /**
     * Affichage page création compte
     * @return page
     */
    @GetMapping("/register")
    public String registerPage(Model model) {
        model.addAttribute("userDto", new UserRegistrationDto());
        return "register";
    }

    /**
     * Action création nouvel utilisateur
     * @param dto données du formulaire
     * @return page
     */
    @PostMapping("/register")
    public String registerUser(@ModelAttribute("userDto") UserRegistrationDto dto) {
        // On ne crée pas un compte à un email déjà existant
        if (userRepository.findByEmail(dto.getEmail()).isPresent()) {
            return "redirect:/register?error";
        }

        // Le pseudo doit être unique
        if (userRepository.existsByUsername(dto.getUsername())) {
            return "redirect:/register?errorUsername";
        }

        // Création utilisateur
        User user = userService.createNewUser(dto);
        // Enregistrement de l'utilisateur
        userRepository.save(user);

        // Création token
        ConfirmationToken confirmationToken = userService.createConfirmationTokenUser(user);
        // Enregistrement du token
        tokenRepository.save(confirmationToken);

        // Envoi Email avec le token
        String link = baseUrl + "/confirm?token=" + confirmationToken.getToken();
        emailService.sendConfirmationEmail(user.getEmail(), user.getFirstName(), link);

        // Redirection vers login avec message spécial
        return "redirect:/login?mailSent";
    }

    /**
     * Action confirmation activation du compte (depuis email)
     * @param token token
     * @return page
     */
    @GetMapping("/confirm")
    @Transactional
    public String confirmToken(@RequestParam("token") String token) {
        // Récupération du token pour savoir s'il existe ou non
        ConfirmationToken confirmToken = tokenRepository.findByToken(token)
                .orElse(null);

        // Contrôles de conformité
        String error = confirmationTokenService.checkConformityTokenUser(confirmToken);
        if (error != null) {
            return error;
        }

        // Validation réussie
        Objects.requireNonNull(confirmToken).setConfirmedAt(LocalDateTime.now());

        // Activation de l'utilisateur
        User user = confirmToken.getUser();
        user.setEnabled(true);
        userRepository.save(user);

        // Création / Enregistrement du bilan de départ
        userBilanService.initializeBilanUser(user);

        return "redirect:/login?verified";
    }
}
