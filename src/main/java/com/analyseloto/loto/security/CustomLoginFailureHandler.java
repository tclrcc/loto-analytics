package com.analyseloto.loto.security;

import com.analyseloto.loto.entity.User;
import com.analyseloto.loto.repository.UserRepository;
import com.analyseloto.loto.service.LoginAttemptService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class CustomLoginFailureHandler extends SimpleUrlAuthenticationFailureHandler {

    private final LoginAttemptService loginAttemptService;
    private final UserRepository userRepository;

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException, ServletException {

        String email = request.getParameter("username");

        // 1. Enregistrer l'échec
        loginAttemptService.loginFailed(email);

        // 2. Vérifier l'état pour le message d'erreur
        Optional<User> userOpt = userRepository.findByEmail(email);

        if (userOpt.isPresent() && !userOpt.get().isAccountNonLocked()) {
            // Cas : Compte verrouillé
            setDefaultFailureUrl("/login?error=locked");
        } else {
            // Cas : Mauvais mot de passe classique
            setDefaultFailureUrl("/login?error=true");
        }

        super.onAuthenticationFailure(request, response, exception);
    }
}