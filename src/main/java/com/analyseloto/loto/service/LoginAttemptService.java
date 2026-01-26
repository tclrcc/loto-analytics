package com.analyseloto.loto.service;

import com.analyseloto.loto.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class LoginAttemptService {
    // Repositories
    private final UserRepository userRepository;

    // Constantes données connexion
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final long LOCK_TIME_DURATION = 15; // minutes

    /**
     * Méthode gestion succès connexion utilisateur
     * @param email mail user
     */
    @Transactional
    public void loginSucceeded(String email) {
        // Si l'utilisateur est présent en base, on débloque son compte en cas de blocage
        userRepository.findByEmail(email).ifPresent(user -> {
            if (user.getFailedAttempt() > 0 || user.isAccountLocked()) {
                user.setFailedAttempt(0);
                user.setAccountLocked(false);
                user.setLockTime(null);
                userRepository.save(user);
            }
        });
    }

    /**
     * Méthode gestion échec connexion utilisateur
     * @param email mail user
     */
    @Transactional
    public void loginFailed(String email) {
        userRepository.findByEmail(email).ifPresent(user -> {
            // On ne touche pas si déjà verrouillé (sauf si le temps est écoulé, géré par isAccountNonLocked)
            if (user.isAccountNonLocked()) {
                if (user.getFailedAttempt() < MAX_FAILED_ATTEMPTS - 1) {
                    user.setFailedAttempt(user.getFailedAttempt() + 1);
                } else {
                    // Verrouillage
                    user.setAccountLocked(true);
                    user.setLockTime(LocalDateTime.now().plusMinutes(LOCK_TIME_DURATION));
                }
                userRepository.save(user);
            }
        });
    }
}
