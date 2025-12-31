package com.analyseloto.loto.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Data
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String email;

    @Column(nullable = false)
    private String password;

    // Infos Personnelles pour l'Astro
    private String firstName;
    private LocalDate birthDate;
    private String birthTime; // Format "HH:mm"
    private String birthCity;
    private String zodiacSign;

    // Pour activer/désactiver les mails
    private boolean subscribeToEmails = true;

    @Column(nullable = false)
    private boolean enabled = false;

    @Column(nullable = false)
    private String role = "USER";

    @Column(name = "failed_attempt")
    private int failedAttempt = 0;

    @Column(name = "account_locked")
    private boolean accountLocked = false;

    @Column(name = "lock_time")
    private LocalDateTime lockTime;

    /**
     * Méthode pour savoir si l'utilisateur est bloqué ou non
     * @return
     */
    public boolean isAccountNonLocked() {
        if (!this.accountLocked) {
            return true; // Pas verrouillé
        }
        return this.lockTime != null && this.lockTime.isBefore(LocalDateTime.now());
    }
}
