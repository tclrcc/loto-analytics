import sys
import json
import numpy as np
import pandas as pd
import os

# On supprime les logs TensorFlow pour ne pas polluer la sortie JSON lue par Java
os.environ['TF_CPP_MIN_LOG_LEVEL'] = '3'
import tensorflow as tf
from tensorflow.keras.models import Sequential
from tensorflow.keras.layers import LSTM, Dense, Dropout

def train_and_predict(csv_path):
    # 1. Chargement des données
    # Le CSV doit avoir les colonnes : boule1, boule2, boule3, boule4, boule5
    try:
        df = pd.read_csv(csv_path)
    except Exception as e:
        return {}

    # On ne garde que les boules
    data = df[['boule1', 'boule2', 'boule3', 'boule4', 'boule5']].values

    # 2. Prétraitement (Normalisation entre 0 et 1 pour le Neural Network)
    data_normalized = data / 50.0  # Car 49 boules max (+ marge)

    # Création des séquences (X = 10 derniers tirages, y = tirage suivant)
    sequence_length = 10
    X, y = [], []

    if len(data) < sequence_length + 1:
        return {} # Pas assez de données

    for i in range(len(data) - sequence_length):
        X.append(data_normalized[i:i+sequence_length])
        y.append(data_normalized[i+sequence_length])

    X = np.array(X)
    y = np.array(y)

    # 3. Modèle LSTM (Architecture simple mais efficace pour les séries temp)
    model = Sequential([
        LSTM(64, return_sequences=True, input_shape=(sequence_length, 5)),
        Dropout(0.2),
        LSTM(32),
        Dropout(0.2),
        Dense(5, activation='sigmoid') # 5 sorties (les 5 boules), entre 0 et 1
    ])

    model.compile(optimizer='adam', loss='mse')

    # Entraînement rapide (Epochs réduites pour la démo, augmenter pour la prod)
    model.fit(X, y, epochs=20, batch_size=16, verbose=0)

    # 4. Prédiction du prochain tirage
    last_sequence = data_normalized[-sequence_length:]
    last_sequence = last_sequence.reshape((1, sequence_length, 5))

    prediction = model.predict(last_sequence, verbose=0)

    # Dénormalisation (0.45 -> 22)
    predicted_numbers = (prediction * 50.0).flatten()
    predicted_numbers = np.round(predicted_numbers).astype(int)

    # Gestion des doublons (si le modèle prédit [10, 10, ...])
    unique_preds = []
    seen = set()
    for n in predicted_numbers:
        val = max(1, min(49, int(n))) # Bornage 1-49
        if val not in seen:
            unique_preds.append(val)
            seen.add(val)

    # Construction du JSON de poids
    # On donne un gros score aux numéros prédits
    weights = {}
    for num in unique_preds:
        weights[str(num)] = 25.0 # Boost de score pour l'algo génétique Java

    return weights

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("{}")
        sys.exit(0)

    file_path = sys.argv[1]
    result = train_and_predict(file_path)
    print(json.dumps(result))
