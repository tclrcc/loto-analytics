import os
import numpy as np
import tensorflow as tf
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel

app = FastAPI(title="Loto AI V8 - Value Engine", version="8.1")
model = None
y_scaler = None

class PredictionRequest(BaseModel):
    current_jackpot: float # On demande au Java de fournir le pactole en cours
    ticket_cost: float = 2.20

@app.on_event("startup")
async def load_model():
    global model, y_scaler
    try:
        model_path = "models/value_model_v8.keras"
        scaler_path = "models/y_scaler.npy"
        if os.path.exists(model_path) and os.path.exists(scaler_path):
            model = tf.keras.models.load_model(model_path)
            y_scaler = np.load(scaler_path)
            print("✅ V8 Value Engine & Scaler: READY")
    except Exception as e:
        print(f"🔥 Error loading model: {e}")

@app.post("/predict")
async def predict(req: PredictionRequest):
    if not model:
        raise HTTPException(status_code=500, detail="Modèle non chargé")

    try:
        # 1. Calcul de l'Espérance Mathématique (EV) simplifiée
        # Probabilité théorique de toucher le gros lot
        prob_jackpot = 1 / 19068840

        # EV = (Probabilité * Gain potentiel) / Mise
        # Note : Dans la réalité, il faut ajouter l'EV des rangs inférieurs
        ev_score = (req.current_jackpot * prob_jackpot) / req.ticket_cost

        # Le SNIPER MODE : On ne joue que si les mathématiques sont avec nous (ou presque)
        # On fixe un seuil acceptable, par exemple 0.8 pour autoriser le jeu
        play_authorized = bool(ev_score > 0.8)

        # 2. Inférence des numéros Value
        X_pred = np.eye(49)
        predictions_scaled = model.predict(X_pred, verbose=0)

        # Dé-standardisation pour récupérer une valeur compréhensible (ex: euros potentiels au rang 4)
        y_mean, y_std = y_scaler[0], y_scaler[1]
        predictions = (predictions_scaled * y_std) + y_mean

        # 3. Formatage
        scores = {}
        for i, pred in enumerate(predictions):
            scores[str(i + 1)] = float(pred[0])

        return {
            "ev_score": round(ev_score, 2),
            "play_authorized": play_authorized,
            "number_scores": scores
        }

    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))
