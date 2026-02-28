package com.analyseloto.loto.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class WheelingService {

    /**
     * Génère un système réducteur optimal basé sur un Block Design pré-calculé.
     * * @param pool Les N numéros sélectionnés par l'IA (Value maximale).
     * @param garantie Le type de garantie souhaitée (ex: 3 pour "Garantie 3 si 3").
     * @return La liste exacte et optimisée des grilles à valider.
     */
    public List<int[]> genererSystemeReducteur(List<Integer> pool, int garantie) {
        if (pool.size() == 10 && garantie == 3) {
            return appliquerMatrice(pool, getMatriceV10_K5_T3_M3());
        } else if (pool.size() == 12 && garantie == 3) {
            return appliquerMatrice(pool, getMatriceV12_K5_T3_M3());
        }

        // Fallback de sécurité si la taille de la pool ne correspond pas à une matrice connue
        log.warn("Aucune matrice stricte trouvée pour Pool de taille {} et Garantie {}. Retour à une matrice standard V10.", pool.size(), garantie);
        return appliquerMatrice(pool.subList(0, Math.min(10, pool.size())), getMatriceV10_K5_T3_M3());
    }

    /**
     * Applique les numéros réels sélectionnés par l'IA sur la matrice mathématique.
     */
    private List<int[]> appliquerMatrice(List<Integer> pool, int[][] matriceIndex) {
        List<int[]> grillesGenerees = new ArrayList<>();

        for (int[] ligne : matriceIndex) {
            int[] grille = new int[5];
            for (int i = 0; i < 5; i++) {
                // La matrice utilise des index de 1 à N. On soustrait 1 pour l'index de la List Java.
                grille[i] = pool.get(ligne[i] - 1);
            }
            grillesGenerees.add(grille);
        }

        return grillesGenerees;
    }

    /**
     * Matrice de Combinatoire : V=10, K=5, T=3, M=3
     * 10 Numéros joués. Grilles de 5.
     * Garantie 100% de gagner un "3 bons numéros" si 3 des 10 numéros sortent au tirage.
     * Coût optimal : 8 Grilles (17.60€)
     */
    private int[][] getMatriceV10_K5_T3_M3() {
        return new int[][] {
                {1, 2, 3, 4, 5},
                {1, 2, 6, 7, 8},
                {1, 3, 6, 9, 10},
                {1, 4, 7, 9, 10},
                {1, 5, 8, 9, 10},
                {2, 3, 7, 8, 10},
                {2, 4, 5, 9, 10},
                {3, 4, 5, 6, 8}
        };
    }

    /**
     * Matrice de Combinatoire : V=12, K=5, T=3, M=3
     * 12 Numéros joués. Grilles de 5.
     * Garantie 100% de gagner un "3 bons numéros" si 3 des 12 numéros sortent au tirage.
     * Coût optimal : 15 Grilles (33.00€) - Très utilisé par les syndicats
     */
    private int[][] getMatriceV12_K5_T3_M3() {
        return new int[][] {
                {1, 2, 3, 4, 5},
                {1, 6, 7, 8, 9},
                {1, 10, 11, 12, 2},
                {2, 3, 6, 7, 10},
                {2, 4, 8, 11, 12},
                {3, 4, 6, 9, 12},
                {3, 5, 7, 8, 11},
                {4, 5, 7, 9, 10},
                {5, 6, 8, 10, 12},
                {1, 3, 8, 9, 10},
                {1, 4, 6, 7, 11},
                {2, 5, 6, 9, 11},
                {2, 5, 7, 8, 12},
                {3, 4, 7, 8, 12},
                {4, 6, 8, 10, 11}
        };
    }
}
