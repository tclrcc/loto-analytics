package com.analyseloto.loto.controller;

import com.analyseloto.loto.entity.User;
import com.analyseloto.loto.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.security.Principal;
import java.time.LocalDate;

@Controller
@RequestMapping("/profile")
@RequiredArgsConstructor
public class ProfileController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    // 1. Afficher la page Profil
    @GetMapping
    public String showProfile(Model model, Principal principal) {
        String email = principal.getName();
        User user = userRepository.findByEmail(email).orElseThrow();
        model.addAttribute("user", user);
        return "profile"; // Renvoie profile.html
    }

    // 2. Mettre à jour les infos personnelles
    @PostMapping("/update")
    public String updateInfo(Principal principal,
                             @RequestParam String firstName,
                             @RequestParam LocalDate birthDate,
                             @RequestParam String birthTime,
                             @RequestParam String birthCity,
                             @RequestParam String zodiacSign,
                             @RequestParam(defaultValue = "false") boolean subscribeToEmails) {

        String email = principal.getName();
        User user = userRepository.findByEmail(email).orElseThrow();

        user.setFirstName(firstName);
        user.setBirthDate(birthDate);
        user.setBirthTime(birthTime);
        user.setBirthCity(birthCity);
        user.setZodiacSign(zodiacSign);
        user.setSubscribeToEmails(subscribeToEmails);

        userRepository.save(user);

        return "redirect:/profile?successInfo";
    }

    // 3. Changer le mot de passe
    @PostMapping("/password")
    public String changePassword(Principal principal,
                                 @RequestParam String currentPassword,
                                 @RequestParam String newPassword) {

        String email = principal.getName();
        User user = userRepository.findByEmail(email).orElseThrow();

        // Vérifier l'ancien mot de passe
        if (!passwordEncoder.matches(currentPassword, user.getPassword())) {
            return "redirect:/profile?errorPass";
        }

        // Sauvegarder le nouveau (crypté)
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        return "redirect:/profile?successPass";
    }
}