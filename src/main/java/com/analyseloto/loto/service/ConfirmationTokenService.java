package com.analyseloto.loto.service;

import com.analyseloto.loto.entity.ConfirmationToken;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@Slf4j
@RequiredArgsConstructor
public class ConfirmationTokenService {

    /**
     * Méthode de vérification de conformité du token d'activation du compte
     * @param confirmToken token d'activation
     * @return Redirection si erreur, sinon null
     */
    public String checkConformityTokenUser(ConfirmationToken confirmToken) {
        if (confirmToken == null) {
            return "redirect:/login?tokenError"; // Token inconnu
        }

        if (confirmToken.getConfirmedAt() != null) {
            return "redirect:/login?alreadyConfirmed"; // Token déjà confirmé
        }

        // Récupération date d'expiration
        LocalDateTime expiredAt = confirmToken.getExpiresAt();
        if (expiredAt.isBefore(LocalDateTime.now())) {
            return "redirect:/login?expired"; // Token expiré
        }

        return null;
    }
}
