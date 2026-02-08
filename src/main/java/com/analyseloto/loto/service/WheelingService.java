package com.analyseloto.loto.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.math3.util.CombinatoricsUtils;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
@Service
public class WheelingService {

    /**
     * Génère un système réducteur (Abbreviated Wheel).
     * @param poolNumeros Liste de vos X meilleurs numéros (ex: 12)
     * @param garantie La garantie de gain (ex: 3 = si les 5 gagnants sont dans le pool, on a au moins une grille à 3 points)
     * @return Liste de grilles (int[]) optimisées
     */
    public List<int[]> genererSystemeReducteur(List<Integer> poolNumeros, int garantie) {
        if (poolNumeros.size() < 5) return new ArrayList<>();

        log.info("⚙️ [WHEELING] Génération système réducteur pour {} numéros (Garantie: {}/5)", poolNumeros.size(), garantie);

        // 1. Générer TOUTES les combinaisons possibles de 5 numéros parmi le pool
        // Attention : C(12,5) = 792, C(15,5) = 3003. Restons sous 15 numéros pour la perf.
        List<Set<Integer>> toutesCombinaisons = new ArrayList<>();
        Iterator<int[]> iterator = CombinatoricsUtils.combinationsIterator(poolNumeros.size(), 5);

        while (iterator.hasNext()) {
            int[] indices = iterator.next();
            Set<Integer> grille = new HashSet<>();
            for (int idx : indices) grille.add(poolNumeros.get(idx));
            toutesCombinaisons.add(grille);
        }

        // 2. Algorithme Glouton (Greedy Set Cover)
        List<int[]> systemeFinal = new ArrayList<>();

        // Tant qu'il reste des combinaisons non couvertes
        while (!toutesCombinaisons.isEmpty()) {
            // Pour aller vite, on prend la première disponible comme "Pivot"
            // (Dans une version V4, on chercherait celle qui couvre le plus de restantes)
            Set<Integer> pivot = toutesCombinaisons.get(0);

            // On l'ajoute à notre sélection
            systemeFinal.add(pivot.stream().mapToInt(Integer::intValue).toArray());

            // On retire de la liste TOUTES les grilles qui sont "couvertes" par ce pivot
            // Une grille est couverte si elle partage au moins 'garantie' numéros avec le pivot
            toutesCombinaisons.removeIf(candidat -> {
                int communs = 0;
                for (Integer n : candidat) {
                    if (pivot.contains(n)) communs++;
                }
                return communs >= garantie;
            });
        }

        log.info("✅ [WHEELING] Réduction terminée : {} grilles générées pour couvrir le pool.", systemeFinal.size());
        return systemeFinal;
    }
}
