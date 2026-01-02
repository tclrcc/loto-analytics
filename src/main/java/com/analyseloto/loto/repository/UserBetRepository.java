package com.analyseloto.loto.repository;

import com.analyseloto.loto.entity.User;
import com.analyseloto.loto.entity.UserBet;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface UserBetRepository extends JpaRepository<UserBet, Long> {
    // Récupérer les paris d'un utilisateur, du plus récent au plus vieux
    List<UserBet> findByUserOrderByDateJeuDesc(User user);
    List<UserBet> findByUserId(Long userId);
    List<UserBet> findByDateJeu(LocalDate dateJeu);
    List<UserBet> findByUser(User user);
}
