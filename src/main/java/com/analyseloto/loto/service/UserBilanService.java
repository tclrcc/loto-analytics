package com.analyseloto.loto.service;

import com.analyseloto.loto.entity.User;
import com.analyseloto.loto.entity.UserBilan;
import com.analyseloto.loto.repository.UserBilanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

@Service
@Slf4j
@RequiredArgsConstructor
public class UserBilanService {
    // Repositories
    private final UserBilanRepository userBilanRepository;

    /**
     * Initialise le bilan de l'utilisateur
     * @param user utilisateur
     */
    public void initializeBilanUser(User user) {
        // Création du bilan initial
        UserBilan bilanInitial = new UserBilan(user, LocalDate.now());
        bilanInitial.setTotalDepense(0.0);
        bilanInitial.setTotalGains(0.0);
        bilanInitial.setSolde(0.0);
        bilanInitial.setRoi(0.0);
        bilanInitial.setNbGrillesJouees(0);
        bilanInitial.setNbGrillesGagnantes(0);

        userBilanRepository.save(bilanInitial);
    }
}
