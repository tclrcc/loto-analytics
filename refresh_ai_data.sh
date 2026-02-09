#!/bin/bash

# Définition des couleurs pour les logs
GREEN='\033[0;32m'
RED='\033[0;31m'
NC='\033[0m' # No Color

echo -e "${GREEN}📊 Extraction des données de production...${NC}"

# 1. Extraction des données (Sur le VPS Hôte)
docker exec -i loto_db psql -U loto -d lotodb -c "COPY (SELECT date_tirage as date, boule1, boule2, boule3, boule4, boule5, numero_chance as chance FROM tirage ORDER BY date_tirage ASC) TO STDOUT WITH CSV HEADER;" > loto_history.csv 2> /dev/null

if [ -s loto_history.csv ]; then
    echo -e "${GREEN}✅ Fichier loto_history.csv généré avec succès. $(wc -l < loto_history.csv) lignes.${NC}"

    # 2. Vérification du conteneur
    if [ ! "$(docker ps -q -f name=loto-ai-engine)" ]; then
        echo "⚠️ Le conteneur loto-ai est éteint. Démarrage..."
        docker-compose up -d loto-ai
        sleep 5
    fi

    # --- PARTIE AJOUTÉE POUR LA V6/V7 ---
    echo -e "${GREEN}🔄 Synchronisation des scripts et données vers le conteneur...${NC}"

    # On envoie le CSV fraîchement généré DANS le conteneur
    docker cp loto_history.csv loto-ai-engine:/app/loto_history.csv

    # On force la mise à jour des scripts Python DANS le conteneur (V4 -> V7)
    docker cp scripts/train_models.py loto-ai-engine:/app/scripts/train_models.py
    docker cp scripts/loto_api.py loto-ai-engine:/app/scripts/loto_api.py
    # ------------------------------------

    # 3. Lancement de l'entraînement
    echo -e "${GREEN}🧠  Lancement du ré-entraînement de l'IA (V7)...${NC}"
    docker exec loto-ai-engine python3 scripts/train_models.py

    # 4. Redémarrage pour charger le nouveau modèle en mémoire
    echo -e "${GREEN}🔄 Redémarrage du moteur d'inférence...${NC}"
    docker-compose restart loto-ai

    echo -e "${GREEN}🚀 Terminé ! L'IA est à jour (V7) et redémarrée.${NC}"
else
    echo -e "${RED}❌ Erreur : Le fichier CSV est vide ou n'a pas pu être créé.${NC}"
    exit 1
fi
