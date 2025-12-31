package com.analyseloto.loto.service;

import com.analyseloto.loto.entity.PasswordResetToken;
import com.analyseloto.loto.entity.User;
import com.analyseloto.loto.repository.PasswordResetTokenRepository;
import com.analyseloto.loto.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PasswordResetService {
    private final UserRepository userRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final EmailService emailService;
    private final PasswordEncoder passwordEncoder;

    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

    @Transactional
    public void processForgotPassword(String email) {
        Optional<User> userOpt = userRepository.findByEmail(email);

        // Sécurité : Si l'email n'existe pas, on ne fait rien mais on ne renvoie pas d'erreur
        // pour ne pas dire aux pirates quels emails sont inscrits.
        if (userOpt.isPresent()) {
            User user = userOpt.get();

            // 1. Générer Token
            String token = UUID.randomUUID().toString();
            PasswordResetToken myToken = new PasswordResetToken(token, user);
            tokenRepository.save(myToken);

            // 2. Envoyer Email
            String link = baseUrl + "/reset-password?token=" + token;
            String subject = "🔑 Réinitialisation de votre mot de passe";
            String body = "<html><body>"
                    + "<p>Bonjour " + user.getFirstName() + ",</p>"
                    + "<p>Vous avez demandé la réinitialisation de votre mot de passe.</p>"
                    + "<p><a href='" + link + "'>Cliquez ici pour changer votre mot de passe</a></p>"
                    + "<p>Ce lien expire dans 15 minutes.</p>"
                    + "</body></html>";

            emailService.sendHtmlEmail(user.getEmail(), subject, body);
        }
    }

    @Transactional
    public boolean resetPassword(String token, String newPassword) {
        Optional<PasswordResetToken> tokenOpt = tokenRepository.findByToken(token);

        if (tokenOpt.isEmpty() || tokenOpt.get().isExpired()) {
            return false;
        }

        PasswordResetToken resetToken = tokenOpt.get();
        User user = resetToken.getUser();

        // Mise à jour du mot de passe
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        // On supprime le token pour qu'il ne serve qu'une fois
        tokenRepository.delete(resetToken);

        return true;
    }
}
