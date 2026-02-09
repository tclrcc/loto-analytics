package com.analyseloto.loto.service;

import com.analyseloto.loto.service.calcul.BitMaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.math3.util.CombinatoricsUtils;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class WheelingService {

    private final BitMaskService bitMaskService;

    /**
     * V5 : Algorithme Glouton Optimisé (Best-Fit Strategy)
     * Cherche à chaque étape la grille qui élimine le plus de combinaisons restantes.
     */
    public List<int[]> genererSystemeReducteur(List<Integer> poolNumeros, int garantie) {
        if (poolNumeros.size() < 5) return new ArrayList<>();

        long startTime = System.currentTimeMillis();
        int k = 5; // Taille grille Loto

        // 1. Générer toutes les combinaisons possibles (L'univers à couvrir)
        // Utilisation de BitMask (Long) pour la performance mémoire et CPU
        List<Long> universNonCouvert = new ArrayList<>();
        Iterator<int[]> iterator = CombinatoricsUtils.combinationsIterator(poolNumeros.size(), k);

        while (iterator.hasNext()) {
            int[] indices = iterator.next();
            List<Integer> combo = new ArrayList<>();
            for (int idx : indices) combo.add(poolNumeros.get(idx));
            universNonCouvert.add(bitMaskService.calculerBitMask(combo));
        }

        log.info("🎯 [V5] Univers total à couvrir : {} combinaisons", universNonCouvert.size());

        List<int[]> systemeFinal = new ArrayList<>();

        // Liste des candidats potentiels (toutes les grilles jouables possibles)
        // Au départ, c'est identique à l'univers, mais on copie pour ne pas altérer l'univers
        List<Long> candidatsJouables = new ArrayList<>(universNonCouvert);

        // 2. Boucle Gloutonne Optimisée
        while (!universNonCouvert.isEmpty()) {
            long meilleurCandidat = -1L;
            int maxCouverture = -1;
            List<Long> indicesCouvertsParMeilleur = null;

            // STRATÉGIE V5 : On teste chaque candidat pour voir lequel "tue" le plus de restants
            // Note: Pour des pools > 20 numéros, il faudra passer à une heuristique aléatoire
            // car cette boucle peut être lourde.
            for (Long candidat : candidatsJouables) {
                int couvertureActuelle = 0;
                // On simule la couverture
                for (Long cible : universNonCouvert) {
                    if (testGarantie(candidat, cible, garantie)) {
                        couvertureActuelle++;
                    }
                }

                if (couvertureActuelle > maxCouverture) {
                    maxCouverture = couvertureActuelle;
                    meilleurCandidat = candidat;
                    // Optimisation : si on couvre tout ce qui reste, on arrête direct
                    if (maxCouverture == universNonCouvert.size()) break;
                }
            }

            if (meilleurCandidat == -1L) break; // Sécurité

            // Ajouter le gagnant au système
            systemeFinal.add(convertMaskToArr(meilleurCandidat));
            candidatsJouables.remove(meilleurCandidat); // On ne peut pas le rejouer

            // Retirer de l'univers tout ce qui est couvert par ce gagnant
            long finalBest = meilleurCandidat;
            universNonCouvert.removeIf(cible -> testGarantie(finalBest, cible, garantie));
        }

        log.info("✅ [V5] Système terminé en {}ms. Grilles générées : {}",
                (System.currentTimeMillis() - startTime), systemeFinal.size());

        return systemeFinal;
    }

    // Vérifie si deux masques partagent au moins 'garantie' bits communs
    private boolean testGarantie(long maskA, long maskB, int garantie) {
        long commun = maskA & maskB;
        return Long.bitCount(commun) >= garantie;
    }

    private int[] convertMaskToArr(long mask) {
        return bitMaskService.decodeBitMask(mask).stream().mapToInt(i->i).toArray();
    }
}
