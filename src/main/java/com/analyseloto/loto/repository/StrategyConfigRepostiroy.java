package com.analyseloto.loto.repository;

import com.analyseloto.loto.entity.StrategyConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface StrategyConfigRepostiroy extends JpaRepository<StrategyConfig, Long> {
    // Récupère le dernier leader enregistré
    Optional<StrategyConfig> findTopByLeaderTrueOrderByDateCalculDesc();

    // Récupère tous les experts d'un batch spécifique (par date exacte)
    List<StrategyConfig> findAllByDateCalcul(LocalDateTime dateCalcul);
}
