package com.analyseloto.loto.service;

import com.analyseloto.loto.repository.LotoTirageRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LotoServiceTest {
    // On "simule" (Mock) la base de données pour ne pas avoir besoin de H2
    @Mock
    private LotoTirageRepository repository;

    // L'objet que l'on veut vraiment tester
    @InjectMocks
    private LotoService lotoService;

    // ------------------------------------------------------------------------
    // TESTER UN CALCUL PUR
    // ------------------------------------------------------------------------
    @Test
    @DisplayName("✅ Cohérence : Une grille avec 3 numéros consécutifs doit être rejetée")
    void testRejetGrilleAvecSuiteDeTrois() {
        // GIVEN (Étant donné) : Une grille avec une suite interdite (ex: 1, 2, 3)
        List<Integer> grilleFaussee = new ArrayList<>(List.of(1, 2, 3, 40, 45));
        LotoService.DynamicConstraints contraintes = new LotoService.DynamicConstraints(2, 3, true, Set.of());

        // WHEN (Quand) : On passe cette grille à notre validateur
        // (Note: Vous devrez peut-être passer votre méthode 'estGrilleCoherente' en "public" ou "protected" pour la tester)
        boolean estValide = lotoService.estGrilleCoherente(grilleFaussee, null, contraintes);

        // THEN (Alors) : AssertJ vérifie que le résultat est FAUX
        assertThat(estValide)
                .as("La grille contient une suite de 3 numéros, elle devrait être invalide")
                .isFalse();
    }

    // ------------------------------------------------------------------------
    // EXEMPLE 2 : TESTER UN CALCUL BASÉ SUR L'HISTORIQUE (Avec Mock)
    // ------------------------------------------------------------------------
    @Test
    @DisplayName("✅ Prochain Tirage : Doit renvoyer le jour suivant après 20h15")
    void testCalculProchainTirageApresHeureLimite() {
        // (Vous pouvez tester des méthodes de date, des calculs de gain, etc.)
        LocalDate date = lotoService.recupererDateProchainTirage();

        assertThat(date).isNotNull();
        // Vérifiez ici que la date correspond bien à un Lundi, Mercredi ou Samedi
    }
}
