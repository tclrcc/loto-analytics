package com.analyseloto.loto.service;

import com.analyseloto.loto.dto.TirageManuelDto;
import com.analyseloto.loto.entity.LotoTirage;
import com.analyseloto.loto.repository.LotoTirageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
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

    // API officielle utilisée par le front FDJ
    private static final String FDJ_API_URL = "https://www.fdj.fr/api/service-draws/v1/games/loto/draws?include=results,addons&range=0-0";

    /**
     * Méthode récupérant automatiquement le dernier tirage du Loto via API
     * @return
     */
    public Optional<LotoTirage> recupererDernierTirage() {
        log.info("🌍 Appel API FDJ...");
        try {
            RestTemplate restTemplate = new RestTemplate();
            HttpHeaders headers = new HttpHeaders();
            // Permet de passer pour un navigateur
            headers.set("User-Agent", "PostmanRuntime/7.32.0");
            // Appel API
            ResponseEntity<String> response = restTemplate.exchange(
                    FDJ_API_URL, HttpMethod.GET, new HttpEntity<>(headers), String.class
            );

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.warn("⚠️ API FDJ a répondu avec le statut : {}", response.getStatusCode());
                return Optional.empty();
            }

            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(response.getBody());

            // Le JSON est un tableau, on prend le premier élément (le plus récent)
            if (root.isArray() && !root.isEmpty()) {
                LotoTirage tirage = traiterJsonTirage(root.get(0));
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
            // 1. Récupération de la date (ex: ""2026-01-03T20:55:00+01:00"")
            String dateStr = drawNode.get("drawn_at").asText().substring(0, 10);
            LocalDate dateTirage = LocalDate.parse(dateStr, DateTimeFormatter.ofPattern("yyyy-MM-dd"));

            // Vérification que le tirage n'existe pas dans la base
            if (tirageRepository.existsByDateTirage(dateTirage)) {
                log.info("📅 Tirage du {} déjà en base.", dateTirage);
                return null;
            }

            // 2. Extraction des boules et de la chance
            List<Integer> boules = new ArrayList<>();
            int numeroChance = -1;

            JsonNode results = drawNode.get("results");
            if (results.isArray()) {
                for (JsonNode result : results) {
                    // draw_index = 1 => tirage principal
                    // draw_index = 2 => second tirage
                    int drawIndex = result.get("draw_index").asInt();
                    // type = "number" => numéro tiré
                    // type = "special" => numéro chance
                    String type = result.get("type").asText();
                    String valueStr = result.get("value").asText();

                    // On ne s'intéresse qu'au tirage principal (LOTO)
                    if (drawIndex != 1 || (!type.equals("number") && !type.equals("special"))) continue;

                    int value = Integer.parseInt(valueStr);

                    if ("number".equals(type)) {
                        boules.add(value); // Cas Boule Classique
                    } else {
                        numeroChance = value; // Cas Numéro Chance
                    }
                }
            }

            // Validation cohérence des boules
            if (boules.size() < 5 || numeroChance == -1) {
                log.error("⚠️ Données incomplètes pour le tirage du {}", dateTirage);
                return null;
            }

            // On trie les boules pour être propre (9, 29, 30...)
            Collections.sort(boules);

            // 4. Création DTO
            TirageManuelDto dto = new TirageManuelDto();
            dto.setDateTirage(dateTirage);
            dto.setBoule1(boules.get(0));
            dto.setBoule2(boules.get(1));
            dto.setBoule3(boules.get(2));
            dto.setBoule4(boules.get(3));
            dto.setBoule5(boules.get(4));
            dto.setNumeroChance(numeroChance);

            // 5. Sauvegarde du tirage
            LotoTirage lotoTirage = lotoService.ajouterTirageManuel(dto);
            log.info("✨ SUCCESS ! Tirage importé : {}", dto);

            return lotoTirage;
        } catch (Exception e) {
            log.error("❌ Erreur parsing JSON", e);
            return null;
        }
    }
}
