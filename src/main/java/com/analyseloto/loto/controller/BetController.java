package com.analyseloto.loto.controller;

import com.analyseloto.loto.entity.User;
import com.analyseloto.loto.entity.UserBet;
import com.analyseloto.loto.enums.BetType;
import com.analyseloto.loto.repository.UserBetRepository;
import com.analyseloto.loto.repository.UserRepository;
import com.analyseloto.loto.service.PdfService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.security.Principal;
import java.time.LocalDate;
import java.util.List;

@Slf4j
@Controller
@RequestMapping("/bets")
@RequiredArgsConstructor
public class BetController {

    private final UserBetRepository betRepository;
    private final UserRepository userRepository;
    private final PdfService pdfService;

    /**
     * Action d'ajout d'une nouvelle grille de jeu
     * @param principal utilisateur
     * @param dateJeu date du tirage
     * @param b1 numéro 1
     * @param b2 numéro 2
     * @param b3 numéro 3
     * @param b4 numéro 4
     * @param b5 numéro 5
     * @param chance numéro chance
     * @param mise mise
     * @return
     */
    @PostMapping("/add")
    public String addBet(Principal principal,
                         @RequestParam LocalDate dateJeu,
                         @RequestParam int b1, @RequestParam int b2, @RequestParam int b3, @RequestParam int b4, @RequestParam int b5,
                         @RequestParam int chance,
                         @RequestParam double mise) {

        try {
            log.info("Tentative d'ajout de grille pour {} : Date={}, M={}", principal.getName(), dateJeu, mise);

            // Vérification utilisateur
            User user = userRepository.findByEmail(principal.getName())
                    .orElseThrow(() -> new RuntimeException("Utilisateur introuvable"));

            // Création de la grille
            UserBet bet = new UserBet();
            bet.setUser(user);
            bet.setDateJeu(dateJeu);
            bet.setB1(b1); bet.setB2(b2); bet.setB3(b3); bet.setB4(b4); bet.setB5(b5);
            bet.setChance(chance);
            bet.setMise(mise);
            bet.setType(BetType.GRILLE);

            // Enregistrement de la grille
            betRepository.save(bet);

            log.info("Grille sauvegardée avec succès ID={}", bet.getId());
            return "redirect:/?betAdded";

        } catch (Exception e) {
            log.error("Erreur lors de l'ajout de la grille sur OVH : ", e);
            return "redirect:/?error=saveFailed";
        }
    }

    /**
     * Action d'ajout d'un Code Loto (ex: A 2563 8547)
     * @param principal utilisateur connecté
     * @param dateJeu date du tirage
     * @param codeLoto le code alphanumérique
     * @param mise coût du jeu
     * @return redirection
     */
    @PostMapping("/add-code")
    public String addCode(Principal principal,
            @RequestParam LocalDate dateJeu,
            @RequestParam String codeLoto,
            @RequestParam double mise) {
        try {
            // Mettre le code en majuscules et sans espaces superflus
            String cleanCode = codeLoto.trim().toUpperCase();

            log.info("Ajout Code Loto pour {} : Date={}, Code={}", principal.getName(), dateJeu, cleanCode);

            // 2. Récupération utilisateur
            User user = userRepository.findByEmail(principal.getName())
                    .orElseThrow(() -> new RuntimeException("Utilisateur introuvable"));

            // 3. Création de l'objet (On ne remplit QUE le code, pas les boules)
            UserBet bet = new UserBet();
            bet.setUser(user);
            bet.setDateJeu(dateJeu);
            bet.setMise(mise);
            bet.setCodeLoto(cleanCode);
            bet.setType(BetType.CODE_LOTO);

            // 4. Sauvegarde
            betRepository.save(bet);

            return "redirect:/?codeAdded";
        } catch (Exception e) {
            log.error("Erreur lors de l'ajout du Code Loto : ", e);
            return "redirect:/?error=saveCodeFailed";
        }
    }

    /**
     * Action de modification de la valeur du gain d'une grille jouée
     * @param principal utilisateur
     * @param betId identifiant de la grille
     * @param gain gain
     * @return
     */
    @PostMapping("/update")
    public String updateGain(Principal principal, @RequestParam Long betId, @RequestParam double gain) {
        try {
            // Récupération de la grille
            UserBet bet = betRepository.findById(betId).orElseThrow();
            // Contrôle si utilisateur de la grille != utilisateur actuel
            if (!bet.getUser().getEmail().equals(principal.getName())) {
                return "redirect:/?error=unauthorized";
            }

            // Enregistrement du nouveau gain
            bet.setGain(gain);
            betRepository.save(bet);
        } catch (Exception e) {
            log.error("Erreur update gain", e);
            return "redirect:/?error=updateFailed";
        }

        // Redirection
        return (gain > 0) ? "redirect:/?win=" + gain : "redirect:/?gainUpdated";
    }

    /**
     * Action de suppression d'une grille
     * @param principal utilisateur
     * @param betId identifiant de la grille
     * @return
     */
    @PostMapping("/delete")
    public String deleteBet(Principal principal, @RequestParam Long betId) {
        try {
            // Récupération de la grille
            UserBet bet = betRepository.findById(betId).orElseThrow();
            // Contrôle possession de la grille de l'utilisateur
            if (bet.getUser().getEmail().equals(principal.getName())) {
                betRepository.delete(bet);
            }
        } catch (Exception e) {
            log.error("Erreur delete", e);
            return "redirect:/?error=deleteFailed";
        }

        // Redirection
        return "redirect:/?betDeleted";
    }

    /**
     * Action d'export des grilles en PDF
     * @param principal
     * @return
     * @throws IOException
     */
    @GetMapping("/export/pdf")
    public ResponseEntity<byte[]> exportBetsToPdf(Principal principal) throws IOException {
        // Récupération de l'utilisateur et de ses grilles
        User user = userRepository.findByEmail(principal.getName()).orElseThrow();
        List<UserBet> bets = betRepository.findByUserOrderByDateJeuDesc(user);

        // Génération du PDF avec headers
        byte[] pdfContent = pdfService.generateBetPdf(bets, user.getFirstName());
        // Construction headers
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDispositionFormData("attachment", "mes_grilles_loto.pdf");

        // Réponse PDF
        return new ResponseEntity<>(pdfContent, headers, HttpStatus.OK);
    }
}
