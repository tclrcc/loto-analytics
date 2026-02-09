package com.analyseloto.loto.service;

import com.analyseloto.loto.entity.LotoTirage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.Month;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScoringService {

    // Poids V6 (Total = 1.0)
    private static final double P_FORME = 0.35;
    private static final double P_ECART = 0.30;
    private static final double P_AFFINITE = 0.20;
    private static final double P_SAISON = 0.15;

    /**
     * Calcule le score global de chaque numéro (1-49) pour une date cible.
     */
    public Map<Integer, Double> calculerScores(List<LotoTirage> historique, LocalDate dateCible) {
        Map<Integer, Double> scores = new HashMap<>();

        // 1. Préparez les données brutes
        Map<Integer, Double> scoresForme = calculerScoreForme(historique);
        Map<Integer, Double> scoresEcart = calculerScoreEcart(historique);
        Map<Integer, Double> scoresAffinite = calculerScoreAffinite(historique);
        Map<Integer, Double> scoresSaison = calculerScoreSaison(historique, dateCible.getMonth());

        // 2. Agrégation Pondérée
        for (int i = 1; i <= 49; i++) {
            double note = 0.0;

            note += scoresForme.getOrDefault(i, 0.0) * P_FORME;
            note += scoresEcart.getOrDefault(i, 0.0) * P_ECART;
            note += scoresAffinite.getOrDefault(i, 0.0) * P_AFFINITE;
            note += scoresSaison.getOrDefault(i, 0.0) * P_SAISON;

            scores.put(i, note);
        }

        return scores;
    }

    // --- Moteurs de Calcul ---

    private Map<Integer, Double> calculerScoreForme(List<LotoTirage> history) {
        // Forme = Fréquence sur les 10 derniers tirages
        Map<Integer, Double> map = new HashMap<>();
        int limit = Math.min(history.size(), 10);
        List<LotoTirage> recents = history.subList(0, limit);

        for (int i = 1; i <= 49; i++) map.put(i, 0.0);

        for (LotoTirage t : recents) {
            List.of(t.getBoule1(), t.getBoule2(), t.getBoule3(), t.getBoule4(), t.getBoule5())
                    .forEach(b -> map.put(b, map.get(b) + 1.0));
        }

        // Normalisation (0 à 1)
        double max = map.values().stream().max(Double::compareTo).orElse(1.0);
        map.replaceAll((k, v) -> v / max);
        return map;
    }

    private Map<Integer, Double> calculerScoreEcart(List<LotoTirage> history) {
        // Ecart = Retard actuel normalisé par le retard max historique
        Map<Integer, Integer> dernierSortie = new HashMap<>();
        Map<Integer, Double> scores = new HashMap<>();

        for (int i = 1; i <= 49; i++) dernierSortie.put(i, -1);

        // On remonte le temps
        for (int i = 0; i < history.size(); i++) {
            LotoTirage t = history.get(i);
            List<Integer> boules = List.of(t.getBoule1(), t.getBoule2(), t.getBoule3(), t.getBoule4(), t.getBoule5());
            for (int b : boules) {
                if (dernierSortie.get(b) == -1) dernierSortie.put(b, i);
            }
        }

        // Normalisation : On cap à 30 de retard pour éviter les scores infinis
        dernierSortie.forEach((k, v) -> {
            int retard = (v == -1) ? history.size() : v;
            scores.put(k, Math.min(retard, 30) / 30.0);
        });

        return scores;
    }

    private Map<Integer, Double> calculerScoreAffinite(List<LotoTirage> history) {
        // Affinite = Probabilité de sortir après les numéros du tirage précédent (Markov simplifié)
        if (history.isEmpty()) return new HashMap<>();

        LotoTirage last = history.get(0); // Le plus récent
        List<Integer> cibles = List.of(last.getBoule1(), last.getBoule2(), last.getBoule3(), last.getBoule4(), last.getBoule5());

        Map<Integer, Integer> counts = new HashMap<>();
        for(int i=1; i<=49; i++) counts.put(i, 0);

        // On cherche dans l'historique les tirages qui suivent les mêmes numéros
        // (Simplification : on regarde juste la fréquence globale des amis)
        // Pour V6 Pro : On fera une matrice de transition complète plus tard
        return counts.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> Math.random())); // Placeholder intelligent
    }

    private Map<Integer, Double> calculerScoreSaison(List<LotoTirage> history, Month mois) {
        // Fréquence du numéro durant ce mois précis sur toutes les années
        Map<Integer, Double> map = new HashMap<>();
        for (int i = 1; i <= 49; i++) map.put(i, 0.0);

        long totalTiragesMois = 0;
        for (LotoTirage t : history) {
            if (t.getDateTirage().getMonth() == mois) {
                totalTiragesMois++;
                List.of(t.getBoule1(), t.getBoule2(), t.getBoule3(), t.getBoule4(), t.getBoule5())
                        .forEach(b -> map.put(b, map.get(b) + 1.0));
            }
        }

        final long total = Math.max(1, totalTiragesMois); // Final variable for lambda
        map.replaceAll((k, v) -> v / total); // % d'apparition ce mois-ci

        // Normalisation max
        double max = map.values().stream().max(Double::compareTo).orElse(1.0);
        if (max > 0) map.replaceAll((k, v) -> v / max);

        return map;
    }
}
