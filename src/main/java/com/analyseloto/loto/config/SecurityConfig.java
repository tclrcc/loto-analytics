package com.analyseloto.loto.config;

import com.analyseloto.loto.repository.UserRepository;
import com.analyseloto.loto.security.CustomLoginFailureHandler;
import com.analyseloto.loto.security.CustomLoginSuccessHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
    @Autowired
    private CustomLoginFailureHandler failureHandler;
    @Autowired
    private CustomLoginSuccessHandler successHandler;

    @Value("${loto.security.remember-key}")
    private String rememberKey;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable) // Désactivé pour simplifier les appels API
                .authorizeHttpRequests(auth -> auth
                        // Pages publiques
                        .requestMatchers("/login", "/register", "/confirm", "/forgot-password", "/reset-password", "/css/**", "/js/**", "/images/**").permitAll()
                        .requestMatchers("/api/validation/**").permitAll()
                        // Pages admin
                        .requestMatchers("/admin/**", "/api/loto/import", "/api/loto/add-result").hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                .formLogin(form -> form
                        .loginPage("/login")
                        .successHandler(successHandler)
                        .failureHandler(failureHandler)
                        .permitAll()
                )
                .rememberMe(remember -> remember
                        .key(rememberKey)
                        .tokenValiditySeconds(2592000) // Durée 30 jours (en secondes)
                        .rememberMeParameter("remember-me")
                        .useSecureCookie(false)
                )
                .logout(logout -> logout
                        .logoutSuccessUrl("/login?logout")
                        .deleteCookies("JSESSIONID", "remember-me") // Suppression des cookies
                        .permitAll()
                );

        return http.build();
    }

    /**
     * Encodeur mot de passe
     * @return bean
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Chargement de l'utilisateur en BDD pour Spring
     * @param userRepo : repo
     * @return Infos User
     */
    @Bean
    public UserDetailsService userDetailsService(UserRepository userRepo) {
        return input -> userRepo.findByEmailOrUsername(input, input)
                .map(u -> org.springframework.security.core.userdetails.User.builder()
                        .username(u.getEmail())
                        .password(u.getPassword())
                        .roles(u.getRole())
                        .disabled(!u.isEnabled())
                        .accountLocked(!u.isAccountNonLocked())
                        .build())
                .orElseThrow(() -> new UsernameNotFoundException("Utilisateur introuvable"));
    }
}
