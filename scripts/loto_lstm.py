import sys
import json
import os
import numpy as np
import pandas as pd
import tensorflow as tf
from tensorflow.keras.models import Sequential
from tensorflow.keras.layers import LSTM, Dense, Dropout, BatchNormalization

# Supprimer les logs TensorFlow
os.environ['TF_CPP_MIN_LOG_LEVEL'] = '3'

def get_multi_hot_encoded(row):
    """Transforme [1, 5, 12, 40, 49] en vecteur de 49 bits [1, 0, 0, 1...]"""
    vector = np.zeros(49)
    for val in row:
        if 1 <= val <= 49:
            vector[val-1] = 1.0
    return vector

def train_and_predict(csv_path):
    try:
        if not os.path.exists(csv_path): return {}
        df = pd.read_csv(csv_path)
    except: return {}

    cols = ['boule1', 'boule2', 'boule3', 'boule4', 'boule5']
    if not all(c in df.columns for c in cols): return {}

    data = df[cols].values
    if len(data) < 50: return {}

    # 1. Préparation des données : Encodage Multi-Hot (Classification)
    # X = Séquence de vecteurs multi-hot
    # y = Le vecteur multi-hot du tirage suivant
    sequence_length = 12
    X, y = [], []

    # Pré-calcul de tous les vecteurs
    vectors = np.array([get_multi_hot_encoded(row) for row in data])

    for i in range(len(vectors) - sequence_length):
        X.append(vectors[i:i+sequence_length])
        y.append(vectors[i+sequence_length])

    X = np.array(X) # Shape: (samples, 12, 49)
    y = np.array(y) # Shape: (samples, 49)

    # 2. Modèle : Classification Multi-Label (Sigmoid + Binary Crossentropy)
    model = Sequential([
        LSTM(128, return_sequences=True, input_shape=(sequence_length, 49)),
        BatchNormalization(),
        Dropout(0.3),
        LSTM(64),
        Dropout(0.3),
        Dense(49, activation='sigmoid') # Probabilité indépendante pour chaque boule
    ])

    model.compile(optimizer='adam', loss='binary_crossentropy')
    
    # Entraînement plus robuste
    model.fit(X, y, epochs=25, batch_size=32, verbose=0)

    # 3. Prédiction
    last_seq = vectors[-sequence_length:].reshape(1, sequence_length, 49)
    probs = model.predict(last_seq, verbose=0)[0] # Array de 49 floats

    # 4. Retourner les probabilités brutes (Java décidera du seuil)
    # Format: {"1": 0.05, "2": 0.85, ...}
    weights = {str(i+1): float(probs[i]) for i in range(49)}
    return weights

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("{}"); sys.exit(0)
    print(json.dumps(train_and_predict(sys.argv[1])))
