package com.analyseloto.loto.controller;

import com.analyseloto.loto.entity.User;
import com.analyseloto.loto.entity.UserBet;
import com.analyseloto.loto.repository.UserBetRepository;
import com.analyseloto.loto.repository.UserRepository;
import com.analyseloto.loto.service.PdfService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j; // 1. Ajoutez les logs
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;
import java.security.Principal;
import java.time.LocalDate;
import java.util.List;

@Slf4j // Permet d'écrire dans la console serveur
@Controller
@RequestMapping("/bets")
@RequiredArgsConstructor
public class BetController {

    private final UserBetRepository betRepository;
    private final UserRepository userRepository;
    private final PdfService pdfService;

    @PostMapping("/add")
    public String addBet(Principal principal,
                         @RequestParam LocalDate dateJeu,
                         @RequestParam int b1, @RequestParam int b2, @RequestParam int b3, @RequestParam int b4, @RequestParam int b5,
                         @RequestParam int chance,
                         @RequestParam double mise) {

        try {
            // 1. Logs pour débuguer sur OVH
            log.info("Tentative d'ajout de grille pour {} : Date={}, M={}", principal.getName(), dateJeu, mise);

            // 2. Vérification utilisateur
            User user = userRepository.findByEmail(principal.getName())
                    .orElseThrow(() -> new RuntimeException("Utilisateur introuvable"));

            // 3. Création
            UserBet bet = new UserBet();
            bet.setUser(user);
            bet.setDateJeu(dateJeu);
            bet.setB1(b1); bet.setB2(b2); bet.setB3(b3); bet.setB4(b4); bet.setB5(b5);
            bet.setChance(chance);
            bet.setMise(mise);

            // 4. Enregistrement
            betRepository.save(bet);

            log.info("Grille sauvegardée avec succès ID={}", bet.getId());
            return "redirect:/?betAdded";

        } catch (Exception e) {
            // 5. GESTION D'ERREUR ROBUSTE
            log.error("Erreur lors de l'ajout de la grille sur OVH : ", e);
            // On redirige avec un paramètre d'erreur pour l'afficher à l'utilisateur
            return "redirect:/?error=saveFailed";
        }
    }

    // ... Le reste (update, delete, export) ne change pas,
    // mais vous pouvez ajouter des try-catch similaires si besoin.

    @PostMapping("/update")
    public String updateGain(Principal principal, @RequestParam Long betId, @RequestParam double gain) {
        try {
            UserBet bet = betRepository.findById(betId).orElseThrow();
            if (!bet.getUser().getEmail().equals(principal.getName())) {
                return "redirect:/?error=unauthorized";
            }
            bet.setGain(gain);
            betRepository.save(bet);
            return (gain > 0) ? "redirect:/?win=" + gain : "redirect:/?gainUpdated";
        } catch (Exception e) {
            log.error("Erreur update gain", e);
            return "redirect:/?error=updateFailed";
        }
    }

    @PostMapping("/delete")
    public String deleteBet(Principal principal, @RequestParam Long betId) {
        try {
            UserBet bet = betRepository.findById(betId).orElseThrow();
            if (bet.getUser().getEmail().equals(principal.getName())) {
                betRepository.delete(bet);
            }
            return "redirect:/?betDeleted";
        } catch (Exception e) {
            log.error("Erreur delete", e);
            return "redirect:/?error=deleteFailed";
        }
    }

    @GetMapping("/export/pdf")
    public ResponseEntity<byte[]> exportBetsToPdf(Principal principal) throws IOException {
        User user = userRepository.findByEmail(principal.getName()).orElseThrow();
        List<UserBet> bets = betRepository.findByUserOrderByDateJeuDesc(user);
        byte[] pdfContent = pdfService.generateBetPdf(bets, user.getFirstName());
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDispositionFormData("attachment", "mes_grilles_loto.pdf");
        return new ResponseEntity<>(pdfContent, headers, HttpStatus.OK);
    }
}