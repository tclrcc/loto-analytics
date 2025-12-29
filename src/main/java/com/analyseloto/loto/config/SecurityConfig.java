package com.analyseloto.loto.config;

import com.analyseloto.loto.repository.UserRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable) // Désactivé pour simplifier les appels API
                .authorizeHttpRequests(auth -> auth
                        // Pages publiques
                        .requestMatchers("/login", "/register", "/confirm", "/css/**", "/js/**", "/images/**").permitAll()
                        // Pages admin
                        .requestMatchers("/admin/**", "/api/loto/import", "/api/loto/add").hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                .formLogin(form -> form
                        .loginPage("/login")
                        .defaultSuccessUrl("/", true) // Redirection après succès
                        .permitAll()
                )
                .logout(logout -> logout
                        .logoutSuccessUrl("/login?logout")
                        .permitAll()
                );

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(); // Pour crypter les mots de passe
    }

    /**
     * Chargement de l'utilisateur en BDD pour Spring
     * @param userRepo : repo
     * @return Infos User
     */
    @Bean
    public UserDetailsService userDetailsService(UserRepository userRepo) {
        return email -> userRepo.findByEmail(email)
                .map(u -> org.springframework.security.core.userdetails.User.builder()
                        .username(u.getEmail())
                        .password(u.getPassword())
                        .roles(u.getRole())
                        .disabled(!u.isEnabled())
                        .build())
                .orElseThrow(() -> new UsernameNotFoundException("Utilisateur introuvable"));
    }
}
