import os
import numpy as np
import tensorflow as tf
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from typing import List

app = FastAPI(title="Loto AI V7", version="7.0")
model = None

# --- DTO ---
class Draw(BaseModel):
    date: str
    b1: int; b2: int; b3: int; b4: int; b5: int; chance: int

class PredictionRequest(BaseModel):
    history: List[Draw]

# --- Feature Engineering (Copie Miroir de train_models.py) ---
def get_features_for_inference(draw: Draw):
    # 1. Base
    boules = [draw.b1, draw.b2, draw.b3, draw.b4, draw.b5]
    vector = np.zeros(49)
    for val in boules:
        if 1 <= val <= 49: vector[val-1] = 1.0

    # 2. Meta-Features
    s = sum(boules)
    feat_sum = s / 255.0

    pairs = len([x for x in boules if x % 2 == 0])
    feat_parity = pairs / 5.0

    finales = sum([x % 10 for x in boules])
    feat_finales = finales / 45.0

    return np.concatenate([vector, [feat_sum, feat_parity, feat_finales]])

@app.on_event("startup")
async def load_model():
    global model
    try:
        if os.path.exists("models/lstm_v7.keras"):
            model = tf.keras.models.load_model("models/lstm_v7.keras")
            print("✅ V7 Engine: READY")
        else:
            print("⚠️ V7 Engine: Model not found")
    except Exception as e:
        print(f"🔥 Error: {e}")

@app.post("/predict")
async def predict(req: PredictionRequest):
    if not model: return {}

    # Préparation Sequence (12 derniers tirages)
    if len(req.history) < 12: return {"error": "Need 12+ draws"}

    recent_history = req.history[-12:] # Les 12 plus récents

    # Construction de la matrice (1, 12, 52)
    sequence = np.array([get_features_for_inference(d) for d in recent_history])
    input_tensor = np.expand_dims(sequence, axis=0) # Shape (1, 12, 52)

    # Inférence
    probs = model.predict(input_tensor, verbose=0)[0] # Shape (49,) car output layer est 49

    # Mapping
    result = {}
    for i, p in enumerate(probs):
        result[str(i+1)] = float(p) * 100.0 # Scaling pour Java

    return result
