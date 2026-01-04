package com.analyseloto.loto.security;

import com.analyseloto.loto.service.LoginAttemptService;
import com.analyseloto.loto.util.Service;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class CustomLoginSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final LoginAttemptService loginAttemptService;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException, ServletException {

        // On récupère l'email depuis l'objet UserDetails de Spring
        UserDetails userDetails = (UserDetails) authentication.getPrincipal();
        String email = userDetails.getUsername();

        // On reset les compteurs
        loginAttemptService.loginSucceeded(email);

        // Vérification si "Se souvenir" est coché pour mail lors de la connexion
        // TODO : terminer cookie se souvenir login email
        String rememberMeValue = request.getParameter("remember-me");
        boolean isRememberMeChecked = rememberMeValue != null &&
                (rememberMeValue.equalsIgnoreCase("on") || rememberMeValue.equalsIgnoreCase("true"));

        if (isRememberMeChecked) {
            // On crée un cookie spécial pour l'email
            Cookie emailCookie = new Cookie("saved_email", email);
            emailCookie.setMaxAge(Service.getSecondsFromDays(30)); // 30 jours
            emailCookie.setPath("/"); // Valable sur tout le site
            emailCookie.setSecure(false);
            emailCookie.setHttpOnly(true);
            response.addCookie(emailCookie);
        } else {
            // Pas coché, on supprime le cookie s'il existait
            Cookie emailCookie = new Cookie("saved_email", null);
            emailCookie.setMaxAge(0);
            emailCookie.setPath("/");
            emailCookie.setSecure(false);
            response.addCookie(emailCookie);
        }

        setDefaultTargetUrl("/");
        super.onAuthenticationSuccess(request, response, authentication);
    }
}