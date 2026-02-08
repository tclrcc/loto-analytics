#!/bin/bash

# Définition des couleurs pour les logs
GREEN='\033[0;32m'
RED='\033[0;31m'
NC='\033[0m' # No Color

echo -e "${GREEN}📊 Extraction des données de production...${NC}"

# 1. Extraction (On ignore les warnings de collation PostgreSQL)
docker exec -i loto_db psql -U loto -d lotodb -c "COPY (SELECT date_tirage as date, boule1, boule2, boule3, boule4, boule5, numero_chance as chance FROM tirage ORDER BY date_tirage ASC) TO STDOUT WITH CSV HEADER;" > loto_history.csv 2> /dev/null

if [ -s loto_history.csv ]; then
    echo -e "${GREEN}✅ Fichier loto_history.csv généré avec succès.$(wc -l < loto_history.csv) lignes.${NC}"

    # 2. On s'assure que le conteneur tourne, sinon on le démarre
    if [ ! "$(docker ps -q -f name=loto-ai-engine)" ]; then
        echo "⚠️ Le conteneur loto-ai est éteint. Démarrage..."
        docker-compose up -d loto-ai
        sleep 5 # On laisse le temps de démarrer
    fi

    # 3. Lancement de l'entraînement (Inside the container)
    echo -e "${GREEN}🧠  Lancement du ré-entraînement de l'IA (V4)...${NC}"
    docker exec loto-ai-engine python3 scripts/train_models.py

    # 4. Redémarrage PROPRE (Force Recreate pour éviter le bug de port)
    echo -e "${GREEN}🔄 Redémarrage du moteur d'inférence...${NC}"
    docker-compose restart loto-ai

    echo -e "${GREEN}🚀 Terminé ! L'IA est à jour et redémarrée.${NC}"
else
    echo -e "${RED}❌ Erreur : Le fichier CSV est vide ou n'a pas pu être créé.${NC}"
    exit 1
fi
