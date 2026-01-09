package com.analyseloto.loto.service;

import com.analyseloto.loto.dto.TirageManuelDto;
import com.analyseloto.loto.entity.LotoTirage;
import com.analyseloto.loto.entity.LotoTirageRank;
import com.analyseloto.loto.repository.LotoTirageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Service
@Slf4j
@RequiredArgsConstructor
public class FdjService {
    // Repositories
    private final LotoTirageRepository tirageRepository;
    // Services
    private final LotoService lotoService;
    // Constantes
    @Value("${fdj.api.url}")
    private String fdjApiUrl;

    private static final String FDJ_API_URL = "https://www.fdj.fr/api/service-draws/v1/games/loto/draws?include=results,ranks&range=0-0";
    /**
     * Méthode récupérant automatiquement le dernier tirage du Loto via API
     * @return
     */
    public Optional<LotoTirage> recupererDernierTirage(boolean manuel) {
        log.info("🌍 Appel API FDJ...");
        try {
            RestTemplate restTemplate = new RestTemplate();
            HttpHeaders headers = new HttpHeaders();
            // Permet de passer pour un navigateur
            headers.set("User-Agent", "PostmanRuntime/7.32.0");
            // Construction URL avec paramètres
            String urlComplete = UriComponentsBuilder.fromUriString(fdjApiUrl)
                    .queryParam("include", "results,ranks")
                    .queryParam("range", "0-0")
                    .toUriString();
            // Appel API
            ResponseEntity<String> response = restTemplate.exchange(
                    urlComplete, HttpMethod.GET, new HttpEntity<>(headers), String.class
            );

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.warn("⚠️ API FDJ a répondu avec le statut : {}", response.getStatusCode());
                return Optional.empty();
            }

            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(response.getBody());

            // Le JSON est un tableau, on prend le premier élément (le plus récent)
            if (root.isArray() && !root.isEmpty()) {
                JsonNode dernierTirageJson = root.get(0);
                LotoTirage tirage = traiterJsonTirage(dernierTirageJson);

                // On vérifie que la réponse envoyée est bien le résultat d'aujourd'hui
                if (!manuel && tirage != null) {
                    LocalDate dateTirage = tirage.getDateTirage();
                    LocalDate aujourdhui = LocalDate.now();
                    if (!dateTirage.equals(aujourdhui)) {
                        log.warn("⚠️ Attention : Le dernier tirage disponible date du {}, ce n'est pas celui d'aujourd'hui !", dateTirage);
                        return Optional.empty();
                    }
                }

                return Optional.ofNullable(tirage);
            } else {
                log.warn("⚠️ Le JSON reçu est valide mais vide ou n'est pas un tableau.");
            }

        } catch (RestClientException e) {
            // Erreurs Réseau (Timeout, DNS, 404, 500...)
            log.error("❌ Erreur de communication avec l'API FDJ : {}", e.getMessage());
        } catch (Exception e) {
            // Autres erreurs imprévues
            log.error("❌ Erreur inconnue lors de la récupération FDJ", e);
        }

        return Optional.empty();
    }

    /**
     * Méthode pour traiter le fichier JSON contenant les 2 derniers résultats du Loto
     * @param drawNode
     * @return
     */
    private LotoTirage traiterJsonTirage(JsonNode drawNode) {
        try {
            // 1. DATE ET VÉRIFICATION (Inchangé)
            String dateStr = drawNode.get("drawn_at").asText().substring(0, 10);
            LocalDate dateTirage = LocalDate.parse(dateStr, DateTimeFormatter.ofPattern("yyyy-MM-dd"));

            if (tirageRepository.existsByDateTirage(dateTirage)) {
                log.info("📅 Tirage du {} déjà en base.", dateTirage);
                return null;
            }

            // 2. EXTRACTION BOULES (Inchangé)
            List<Integer> boules = new ArrayList<>();
            int numeroChance = -1;

            JsonNode results = drawNode.get("results");
            if (results.isArray()) {
                for (JsonNode result : results) {
                    int drawIndex = result.path("draw_index").asInt();
                    String type = result.path("type").asText();
                    String valueStr = result.path("value").asText();

                    if (drawIndex != 1 || (!"number".equals(type) && !"special".equals(type))) continue;

                    int value = Integer.parseInt(valueStr);
                    if ("number".equals(type)) boules.add(value);
                    else numeroChance = value;
                }
            }

            if (boules.size() < 5 || numeroChance == -1) {
                log.error("⚠️ Données incomplètes pour le tirage du {}", dateTirage);
                return null;
            }
            Collections.sort(boules);

            // 3. SAUVEGARDE TIRAGE (Inchangé)
            TirageManuelDto dto = new TirageManuelDto();
            dto.setDateTirage(dateTirage);
            dto.setBoule1(boules.get(0));
            dto.setBoule2(boules.get(1));
            dto.setBoule3(boules.get(2));
            dto.setBoule4(boules.get(3));
            dto.setBoule5(boules.get(4));
            dto.setNumeroChance(numeroChance);

            LotoTirage lotoTirage = lotoService.ajouterTirageManuel(dto);
            log.info("✨ Tirage principal importé : {}", dto);

            // --- 4. TRAITEMENT DES RANGS (CORRIGÉ SELON TON JSON) ---
            JsonNode ranksNode = drawNode.get("ranks");
            if (ranksNode != null && ranksNode.isArray()) {
                boolean ranksAdded = false;

                for (JsonNode r : ranksNode) {
                    // 1. Vérifier qu'on est sur le tirage principal (index 1)
                    // Le JSON montre aussi les rangs du "Second Tirage" (index 2) qu'on veut ignorer
                    int drawIndex = r.path("draw_index").asInt(0);
                    if (drawIndex != 1) continue;

                    // 2. Récupérer le numéro du rang (c'est "position" dans ton JSON)
                    int rankNum = r.path("position").asInt(0);

                    // 3. Récupérer le gain ("amount" est en centimes ! Ex: 300000000 -> 3M€)
                    double amountCentimes = r.path("amount").asDouble(0.0);
                    double prize = amountCentimes / 100.0; // Conversion en Euros

                    // 4. Récupérer les gagnants (C'est dans un tableau "winners")
                    int winners = 0;
                    JsonNode winnersArray = r.path("winners");
                    if (winnersArray.isArray() && !winnersArray.isEmpty()) {
                        // On prend le premier élément du tableau winners
                        winners = winnersArray.get(0).path("count").asInt(0);
                    }

                    // 5. On ne garde que les rangs "normal" (Pas le rang "raffle" codes loto)
                    String typeRank = r.path("type").asText();

                    if (rankNum > 0 && "normal".equals(typeRank)) {
                        LotoTirageRank rankObj = new LotoTirageRank(rankNum, winners, prize);
                        lotoTirage.addRank(rankObj);
                        ranksAdded = true;
                    }
                }

                if (ranksAdded) {
                    tirageRepository.save(lotoTirage);
                    log.info("📊 Rangs ajoutés avec succès pour le tirage du {}", dateTirage);
                } else {
                    log.warn("⚠️ Aucun rang pertinent trouvé pour ce tirage.");
                }
            }

            return lotoTirage;

        } catch (Exception e) {
            log.error("❌ Erreur parsing JSON", e);
            return null;
        }
    }
}
