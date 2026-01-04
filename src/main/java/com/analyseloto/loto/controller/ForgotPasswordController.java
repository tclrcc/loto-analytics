package com.analyseloto.loto.controller;

import com.analyseloto.loto.service.PasswordResetService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@RequiredArgsConstructor
public class ForgotPasswordController {
    // Service
    private final PasswordResetService passwordResetService;

    /**
     * Affichage page "Mot de passe oublié"
     * @return
     */
    @GetMapping("/forgot-password")
    public String showForgotPasswordForm() {
        return "forgot_password";
    }

    /**
     * Action traitant la demande d'envoi d'email pour réinitialiser le mot de passe
     * @param email
     * @return
     */
    @PostMapping("/forgot-password")
    public String processForgotPassword(@RequestParam String email) {
        passwordResetService.processForgotPassword(email);

        return "redirect:/forgot-password?sent";
    }

    /**
     * Affichage formulaire pour remplir le nouveau mot de passe
     * @param token
     * @param model
     * @return
     */
    @GetMapping("/reset-password")
    public String showResetPasswordForm(@RequestParam String token, Model model) {
        model.addAttribute("token", token);
        return "reset_password";
    }

    /**
     * Action traitant la réinitialisation du mot de passe
     * @param token
     * @param password
     * @return
     */
    @PostMapping("/reset-password")
    public String processResetPassword(@RequestParam String token, @RequestParam String password) {
        boolean result = passwordResetService.resetPassword(token, password);

        if (!result) {
            return "redirect:/login?tokenError";
        }
        return "redirect:/login?resetSuccess";
    }
}