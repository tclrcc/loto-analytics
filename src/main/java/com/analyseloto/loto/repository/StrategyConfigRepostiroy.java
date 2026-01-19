package com.analyseloto.loto.repository;

import com.analyseloto.loto.entity.StrategyConfig;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface StrategyConfigRepostiroy extends JpaRepository<StrategyConfig, Long> {
    Optional<StrategyConfig> findTopByOrderByDateCalculDesc();
}
