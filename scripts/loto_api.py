import os
import numpy as np
import tensorflow as tf
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from typing import List, Optional

app = FastAPI(title="Loto AI V8 - Value Engine", version="8.0")
model = None

# --- DTO (Maintenu pour la rétrocompatibilité avec LotoService.java) ---
class Draw(BaseModel):
    date: Optional[str] = None
    b1: int = 0; b2: int = 0; b3: int = 0; b4: int = 0; b5: int = 0; chance: int = 0

class PredictionRequest(BaseModel):
    history: Optional[List[Draw]] = None

@app.on_event("startup")
async def load_model():
    global model
    try:
        model_path = "models/value_model_v8.keras"
        if os.path.exists(model_path):
            model = tf.keras.models.load_model(model_path)
            print("✅ V8 Value Engine: READY")
        else:
            print(f"⚠️ V8 Value Engine: Modèle introuvable ({model_path}). N'oublie pas d'exécuter train_models.py !")
    except Exception as e:
        print(f"🔥 Error loading model: {e}")

@app.post("/predict")
async def predict(req: PredictionRequest = None):
    if not model:
        raise HTTPException(status_code=500, detail="Modèle non chargé")

    try:
        # 1. Création de la matrice identité (49x49)
        # Chaque ligne = 1 grille fictive contenant uniquement 1 numéro.
        # Objectif : Demander au modèle la valeur isolée de chaque numéro.
        X_pred = np.eye(49)

        # 2. Inférence de Masse (Ultra rapide)
        # Le modèle renvoie un tableau de 49 scores de rentabilité/impopularité
        predictions = model.predict(X_pred, verbose=0)

        # 3. Formatage de la réponse pour le Java
        result = {}
        for i, pred in enumerate(predictions):
            # pred[0] car l'output du modèle est (49, 1)
            # On stocke le score (Value) de la boule (i + 1)
            result[str(i + 1)] = float(pred[0])

        return result

    except Exception as e:
        print(f"Erreur Inférence: {e}")
        raise HTTPException(status_code=500, detail=str(e))

if __name__ == '__main__':
    import uvicorn
    uvicorn.run(app, host='0.0.0.0', port=8000)
