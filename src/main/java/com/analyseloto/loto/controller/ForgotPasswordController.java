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

    private final PasswordResetService passwordResetService;

    // 1. Afficher la page "Mot de passe oublié"
    @GetMapping("/forgot-password")
    public String showForgotPasswordForm() {
        return "forgot_password";
    }

    // 2. Traiter la demande d'email
    @PostMapping("/forgot-password")
    public String processForgotPassword(@RequestParam String email) {
        passwordResetService.processForgotPassword(email);
        // On redirige toujours vers succès pour ne pas fuiter d'infos
        return "redirect:/forgot-password?sent";
    }

    // 3. Afficher le formulaire de nouveau mot de passe (après clic mail)
    @GetMapping("/reset-password")
    public String showResetPasswordForm(@RequestParam String token, Model model) {
        model.addAttribute("token", token);
        return "reset_password";
    }

    // 4. Traiter le changement de mot de passe
    @PostMapping("/reset-password")
    public String processResetPassword(@RequestParam String token, @RequestParam String password) {
        boolean result = passwordResetService.resetPassword(token, password);

        if (!result) {
            return "redirect:/login?tokenError";
        }
        return "redirect:/login?resetSuccess";
    }
}