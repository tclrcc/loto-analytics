package com.analyseloto.loto.controller;

import com.analyseloto.loto.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/validation")
@RequiredArgsConstructor
public class UserValidationController {
    private final UserRepository userRepository;

    @GetMapping("/check-username")
    public Map<String, Boolean> checkUsername(@RequestParam("value") String value) {
        // Renvoie true si le pseudo est Disponible
        boolean exists = userRepository.existsByUsername(value);
        return Map.of("available", !exists);
    }

    @GetMapping("/check-email")
    public Map<String, Boolean> checkEmail(@RequestParam("value") String value) {
        // Renvoie true si l'email est Disponible
        boolean exists = userRepository.findByEmail(value).isPresent();
        return Map.of("available", !exists);
    }
}
