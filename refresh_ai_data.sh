#!/bin/bash

# Définition des couleurs pour les logs
GREEN='\033[0;32m'
RED='\033[0;31m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

echo -e "${BLUE}====================================================${NC}"
echo -e "${BLUE}   LOTO AI V8 - VALUE ENGINE : SYNCHRONISATION      ${NC}"
echo -e "${BLUE}====================================================${NC}"

echo -e "${GREEN}📊 Extraction de l'historique depuis PostgreSQL...${NC}"

# 1. Extraction des données
docker exec -i loto_db psql -U loto -d lotodb -c "COPY (SELECT date_tirage as date, boule1, boule2, boule3, boule4, boule5, numero_chance as chance FROM tirage ORDER BY date_tirage ASC) TO STDOUT WITH CSV HEADER;" > loto_history.csv 2> /dev/null

if [ -s loto_history.csv ]; then
    echo -e "${GREEN}✅ Fichier loto_history.csv mis à jour. $(wc -l < loto_history.csv) tirages exportés.${NC}"

    # 2. Vérification du conteneur API Python
    if [ ! "$(docker ps -q -f name=loto-ai-engine)" ]; then
        echo -e "⚠️ Le conteneur loto-ai-engine est éteint. Démarrage en cours..."
        docker-compose up -d loto-ai
        sleep 5
    fi

    # Plus besoin de docker cp ici, les Volumes s'en chargent !

    # 3. Lancement de l'entraînement V8 (Value Regressor)
    echo -e "${GREEN}🧠 Apprentissage des biais humains et de l'impopularité (V8)...${NC}"
    docker exec loto-ai-engine python3 scripts/train_models.py

    # 4. Redémarrage pour charger le nouveau modèle keras en RAM
    echo -e "${GREEN}⚡ Redémarrage de l'API FastAPI pour charger value_model_v8.keras...${NC}"
    docker-compose restart loto-ai

    echo -e "${GREEN}🚀 TERMINÉ ! Le Sniper Mode est armé avec le nouveau modèle V8.${NC}"
else
    echo -e "${RED}❌ Erreur : Le fichier CSV est vide ou n'a pas pu être créé.${NC}"
    exit 1
fi
