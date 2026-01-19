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
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.StreamSupport;

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

    // Regex pour détecter un code loto : 1 Lettre, espace optionnel, 8 chiffres (ex: A 1234 5678 ou A12345678)
    private static final Pattern CODE_LOTO_PATTERN = Pattern.compile("^[A-Z]\\s?[0-9]{4}\\s?[0-9]{4}$|^[A-Z][0-9]{8}$");

    /**
     * Méthode récupérant automatiquement le dernier tirage du Loto via API
     * @return
     */
    public Optional<LotoTirage> recupererDernierTirage(boolean manuel) {
        log.info("🌍 Appel API FDJ (Recherche intelligente)...");
        try {
            RestTemplate restTemplate = new RestTemplate();
            HttpHeaders headers = new HttpHeaders();
            // User-Agent standard pour éviter d'être bloqué
            headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");

            // On demande les 4 derniers tirages
            String urlComplete = UriComponentsBuilder.fromUriString(fdjApiUrl)
                    .queryParam("include", "results,ranks")
                    .queryParam("range", "0-3")
                    .toUriString();

            ResponseEntity<String> response = restTemplate.exchange(
                    urlComplete, HttpMethod.GET, new HttpEntity<>(headers), String.class
            );

            // Vérification réponse
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.warn("⚠️ API FDJ erreur : {}", response.getStatusCode());
                return Optional.empty();
            }

            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(response.getBody());

            if (root.isArray() && !root.isEmpty()) {
                // 1. Filtrage et Recherche du plus récent
                Optional<JsonNode> meilleurTirageOpt = StreamSupport.stream(root.spliterator(), false)
                        .filter(node -> node.has("drawn_at")) // Doit avoir une date
                        .sorted((n1, n2) -> {
                            // Tri DESCENDANT (plus récent en premier)
                            String d1 = n1.get("drawn_at").asText();
                            String d2 = n2.get("drawn_at").asText();
                            return d2.compareTo(d1);
                        })
                        .filter(node -> {
                            // Filtre anti-futur (avec ZonedDateTime pour être précis)
                            String dateStr = node.get("drawn_at").asText();
                            ZonedDateTime drawnAt = ZonedDateTime.parse(dateStr);
                            // On ajoute une marge de 1h au cas où les horloges diffèrent légèrement
                            return drawnAt.isBefore(ZonedDateTime.now().plusHours(1));
                        })
                        .findFirst(); // On prend le premier (donc le plus récent valide)

                if (meilleurTirageOpt.isEmpty()) {
                    log.warn("⚠️ Aucun tirage valide trouvé (tous rejetés par les filtres).");
                    return Optional.empty();
                }

                JsonNode tirageCibleJson = meilleurTirageOpt.get();

                // Parsing propre de la date pour l'affichage et le contrôle
                String dateStrFull = tirageCibleJson.get("drawn_at").asText();
                ZonedDateTime zdt = ZonedDateTime.parse(dateStrFull);
                LocalDate dateTirage = zdt.toLocalDate();

                log.info("🔎 Tirage candidat : {} (Reçu: {})", dateTirage, dateStrFull);

                // 2. Vérification de date pour le CRON (Automatique)
                if (!manuel) {
                    LocalDate aujourdhui = LocalDate.now();
                    if (!dateTirage.equals(aujourdhui)) {
                        log.warn("⏳ Le dernier tirage dispo date du {}, mais on est le {}. Résultat pas encore publié.", dateTirage, aujourdhui);
                        return Optional.empty();
                    }
                }

                // 3. Conversion et Sauvegarde
                // La méthode traiterJsonTirage doit gérer l'idempotence (vérifier si existe déjà)
                LotoTirage tirage = traiterJsonTirage(tirageCibleJson);

                // Si traiterJsonTirage renvoie null (ex: existe déjà), on gère
                return Optional.ofNullable(tirage);

            } else {
                log.warn("⚠️ JSON vide ou pas un tableau.");
            }

        } catch (Exception e) {
            log.error("❌ Erreur critique FDJ", e);
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
            List<String> codesGagnants = new ArrayList<>();
            int numeroChance = -1;

            JsonNode results = drawNode.get("results");
            if (results.isArray()) {
                for (JsonNode result : results) {
                    String type = result.path("type").asText();
                    String valueStr = result.path("value").asText();
                    int drawIndex = result.path("draw_index").asInt();

                    if (drawIndex == 1) {
                        if ("number".equals(type)) {
                            boules.add(Integer.parseInt(valueStr));
                        } else if ("special".equals(type)) {
                            numeroChance = Integer.parseInt(valueStr);
                        }
                    }

                    if ("string".equals(type) && valueStr != null) {
                        // On nettoie la valeur (Majuscule, Trim)
                        String cleanVal = valueStr.trim().toUpperCase();
                        if (CODE_LOTO_PATTERN.matcher(cleanVal).matches()) {
                            // On normalise (suppression des espaces pour stockage: A 1234 5678 -> A12345678)
                            // C'est plus simple pour comparer ensuite
                            codesGagnants.add(cleanVal.replaceAll("\\s", ""));
                        }
                    }
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

            // Ajout des infos du tirage (sauf codes loto)
            LotoTirage lotoTirage = lotoService.ajouterTirageManuel(dto);
            // Ajout des codes loto
            lotoTirage.setWinningCodes(codesGagnants);

            log.info("✨ Tirage principal importé : {} | Codes trouvés : {}", dto, codesGagnants.size());

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
