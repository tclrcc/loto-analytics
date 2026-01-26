package com.analyseloto.loto.service;

import com.analyseloto.loto.dto.UserStatsDto;
import com.analyseloto.loto.entity.User;
import com.analyseloto.loto.entity.UserBet;
import com.analyseloto.loto.entity.UserBilan;
import com.analyseloto.loto.enums.BetType;
import com.analyseloto.loto.repository.UserBetRepository;
import com.analyseloto.loto.repository.UserBilanRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@Slf4j
@RequiredArgsConstructor
public class UserStatsService {
    // Repositories
    private final UserBetRepository betRepository;
    private final UserBilanRepository userBilanRepository;

    /**
     * Méthode calculant les statistiques / bilan du joueur
     * @param user utilisateur
     * @return DTO stats utilisateur
     */
    public UserStatsDto calculerStatistiquesJoueur(User user) {
        // Création DTO stats
        UserStatsDto stats = new UserStatsDto();

        // Récupération de l'historique des bilans financiers
        List<UserBilan> historique = userBilanRepository.findByUserOrderByDateBilanAsc(user);
        stats.setHistoriqueFinancier(historique);

        // Si le joueur a un historique, on prend le dernier bilan pour l'en-tête (Ultra-rapide)
        if (!historique.isEmpty()) {
            UserBilan dernierBilan = historique.get(historique.size() - 1);
            stats.setDepenseTotale(dernierBilan.getTotalDepense());
            stats.setGainTotal(dernierBilan.getTotalGains());
        } else {
            stats.setDepenseTotale(0.0);
            stats.setGainTotal(0.0);
        }

        // -------------------------------------------------------------
        // 2. ANALYSE DU STYLE DE JEU (Les grilles détaillées)
        // -------------------------------------------------------------
        // Récupérations des grilles de l'utilisateur
        List<UserBet> bets = betRepository.findByUserOrderByDateJeuDesc(user);
        stats.setTotalGrilles(bets.size());

        // Si aucune grille n'a été jouée, on s'arrête ici
        if (bets.isEmpty()) return stats;

        // Déclaration des variables
        Map<Integer, Integer> freqBoules = new HashMap<>();
        Map<Integer, Integer> freqChance = new HashMap<>();
        long totalSomme = 0;
        int countPairs = 0;
        int totalNumerosJoues = 0;

        // Création map performances selon les jours
        Map<String, UserStatsDto.DayPerformance> dayStats = new LinkedHashMap<>();
        dayStats.put("MONDAY", new UserStatsDto.DayPerformance("Lundi"));
        dayStats.put("WEDNESDAY", new UserStatsDto.DayPerformance("Mercredi"));
        dayStats.put("SATURDAY", new UserStatsDto.DayPerformance("Samedi"));

        // Parcourt toutes les grilles
        for (UserBet bet : bets) {
            // On s'assure que c'est bien une grille
            boolean isGrille = BetType.GRILLE.equals(bet.getType()) && bet.getB1() != null;

            if (isGrille) {
                // Liste des boules
                List<Integer> gr = List.of(bet.getB1(), bet.getB2(), bet.getB3(), bet.getB4(), bet.getB5());

                // Calcul somme
                totalSomme += gr.stream().mapToInt(Integer::intValue).sum();
                for (Integer n : gr) {
                    freqBoules.merge(n, 1, Integer::sum);
                    if (n % 2 == 0) countPairs++;
                    totalNumerosJoues++;
                }

                if (bet.getChance() != null) freqChance.merge(bet.getChance(), 1, Integer::sum);
            }

            // Ajout des gains au jour défini
            String dayKey = bet.getDateJeu().getDayOfWeek().name();
            if (dayStats.containsKey(dayKey)) {
                UserStatsDto.DayPerformance p = dayStats.get(dayKey);
                p.setNbJeux(p.getNbJeux() + 1);
                p.setDepense(p.getDepense() + bet.getMise());
                if (bet.getGain() != null) p.setGains(p.getGains() + bet.getGain());
            }
        }
        stats.setPerformanceParJour(dayStats);

        // Calcul moyenne somme des boules
        long nbGrillesReelles = bets.stream().filter(b -> BetType.GRILLE.equals(b.getType()) && b.getB1() != null).count();
        if (nbGrillesReelles > 0) {
            stats.setMoyenneSomme(Math.round((double) totalSomme / nbGrillesReelles));
        } else {
            stats.setMoyenneSomme(0);
        }

        stats.setTotalPairsJoues(countPairs);
        stats.setTotalImpairsJoues(totalNumerosJoues - countPairs);

        // Calcul de la parité (Pairs / Impairs)
        if (totalNumerosJoues > 0) {
            double ratioPair = (double) countPairs / totalNumerosJoues;
            int p = (int) Math.round(ratioPair * 5);
            stats.setPariteMoyenne(p + " Pairs / " + (5 - p) + " Impairs");
        } else {
            stats.setPariteMoyenne("N/A");
        }

        // On calcule les 5 boules plus jouées
        stats.setTopBoules(freqBoules.entrySet().stream()
                .sorted((e1, e2) -> e2.getValue().compareTo(e1.getValue()))
                .limit(5).map(e -> new UserStatsDto.StatNumero(e.getKey(), e.getValue())).toList());

        // On calcule les 3 chances plus jouées
        stats.setTopChance(freqChance.entrySet().stream()
                .sorted((e1, e2) -> e2.getValue().compareTo(e1.getValue()))
                .limit(3).map(e -> new UserStatsDto.StatNumero(e.getKey(), e.getValue())).toList());

        // Remplissage des boules jamais jouées par l'utilisateur
        List<Integer> jamais = new ArrayList<>();
        for(int i=1; i<=49; i++) {
            if(!freqBoules.containsKey(i)) jamais.add(i);
        }
        stats.setNumJamaisJoues(jamais);

        return stats;
    }
}
