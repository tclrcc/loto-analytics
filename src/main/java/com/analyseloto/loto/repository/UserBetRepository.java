package com.analyseloto.loto.repository;

import com.analyseloto.loto.entity.User;
import com.analyseloto.loto.entity.UserBet;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface UserBetRepository extends JpaRepository<UserBet, Long> {
    /**
     * Recherche des grilles d'un utilisateur, triés par date de jeu décroissante
     * @param user utilisateur
     * @return liste des grilles
     */
    List<UserBet> findByUserOrderByDateJeuDesc(User user);

    /**
     * Recherche des grilles d'un utilisateur pour une date de jeu donnée
     * @param user utilisateur
     * @param dateJeu date de jeu
     * @return liste des grilles
     */
    List<UserBet> findByUserAndDateJeu(User user, LocalDate dateJeu);

    /**
     * Recherche des grilles pour une date de jeu donnée
     * @param dateJeu date de jeu
     * @return liste des grilles
     */
    List<UserBet> findByDateJeu(LocalDate dateJeu);

    /**
     * Recherche des grilles pour une date de jeu donnée et sans gain attribué
     * @param dateJeu date de jeu
     * @return liste des grilles
     */
    List<UserBet> findByDateJeuAndGainIsNull(LocalDate dateJeu);

    /**
     * Recherche des grilles d'un utilisateur
     * @param user utilisateur
     * @return liste des grilles
     */
    List<UserBet> findByUser(User user);
}
