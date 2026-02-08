import os
import sys
import numpy as np
import pandas as pd
import tensorflow as tf
import xgboost as xgb
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from typing import List

# Init API
app = FastAPI(title="Loto Analytics AI V4", version="4.0")

# --- Modèles Persistants (Singleton) ---
models = {
    "lstm": None,
    "xgb": None
}

# --- Schémas de Données (DTO) ---
class Draw(BaseModel):
    date: str
    b1: int
    b2: int
    b3: int
    b4: int
    b5: int
    chance: int

class PredictionRequest(BaseModel):
    history: List[Draw]

# --- Chargement au Démarrage ---
@app.on_event("startup")
async def load_models():
    print("🔄 [API] Chargement des modèles en mémoire...")
    try:
        # LSTM
        if os.path.exists("models/lstm_v4.h5"):
            models["lstm"] = tf.keras.models.load_model("models/lstm_v4.h5")
            print("✅ LSTM Engine: CHARGÉ")
        else:
            print("⚠️ LSTM Engine: Fichier introuvable (Lancez train_models.py)")

        # XGBoost
        if os.path.exists("models/xgb_v4.json"):
            models["xgb"] = xgb.Booster()
            models["xgb"].load_model("models/xgb_v4.json")
            print("✅ XGBoost Engine: CHARGÉ")
        else:
            print("⚠️ XGBoost Engine: Fichier introuvable")

    except Exception as e:
        print(f"🔥 Erreur critique chargement modèles: {str(e)}")

# --- Logique de Prédiction ---
def prepare_lstm_input(draws: List[Draw]):
    # On prend les 12 derniers tirages pour la séquence
    data = [[d.b1, d.b2, d.b3, d.b4, d.b5] for d in draws]
    last_seq = data[-12:]

    # Si pas assez d'historique
    if len(last_seq) < 12: return None

    # Encodage Multi-Hot (Même logique que l'entraînement)
    sequence = []
    for row in last_seq:
        vec = np.zeros(49)
        for val in row:
            if 1 <= val <= 49: vec[val-1] = 1.0
        sequence.append(vec)

    return np.array([sequence]) # Shape (1, 12, 49)

@app.post("/predict")
async def predict(req: PredictionRequest):
    if not models["lstm"]:
        raise HTTPException(status_code=503, detail="Modèles non chargés. Train required.")

    if len(req.history) < 15:
        return {"error": "Historique insuffisant"}

    # 1. Inférence LSTM
    lstm_input = prepare_lstm_input(req.history)
    if lstm_input is None: return {}

    lstm_probs = models["lstm"].predict(lstm_input, verbose=0)[0]

    # 2. Inférence XGBoost (Placeholder pour V4.0)
    # Dans la V4.1, nous ferons le feature engineering ici

    # 3. Fusion et Normalisation
    final_weights = {}
    for i in range(49):
        # Score brut (0.0 à 1.0)
        prob = float(lstm_probs[i])

        # On booste le signal pour que Java le prenne au sérieux
        # Un score de 0.1 (10%) devient +10.0 dans le système de vote Java
        final_weights[str(i+1)] = prob * 100.0

    return final_weights
