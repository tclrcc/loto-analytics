# scripts/loto_lstm.py
import sys
import json
import os
import numpy as np
import pandas as pd

# Désactive les logs verbeux de TensorFlow pour ne pas polluer la sortie JSON
os.environ['TF_CPP_MIN_LOG_LEVEL'] = '3' 
import tensorflow as tf
from tensorflow.keras.models import Sequential
from tensorflow.keras.layers import LSTM, Dense, Dropout

def train_and_predict(csv_path):
    """
    Entraîne un modèle LSTM à la volée et retourne des poids pour les boules probables.
    """
    weights = {}
    
    # 1. Chargement et Sécurisation
    try:
        if not os.path.exists(csv_path):
            return {}
        df = pd.read_csv(csv_path)
    except Exception:
        return {}

    # On s'attend aux colonnes boule1..boule5
    required_cols = ['boule1', 'boule2', 'boule3', 'boule4', 'boule5']
    if not all(col in df.columns for col in required_cols):
        return {}

    data = df[required_cols].values
    
    # Pas assez de données pour faire une séquence (besoin d'au moins 11 tirages pour une seq de 10)
    if len(data) < 15: 
        return {}

    # 2. Normalisation (Neural Networks aiment les données entre 0 et 1)
    # On divise par 50 car les boules vont de 1 à 49.
    data_normalized = data / 50.0

    # 3. Création des séquences (Sliding Window)
    sequence_length = 10 # On regarde les 10 derniers tirages pour prédire le suivant
    X, y = [], []
    
    for i in range(len(data) - sequence_length):
        X.append(data_normalized[i:i+sequence_length])
        y.append(data_normalized[i+sequence_length])

    X = np.array(X)
    y = np.array(y)

    # 4. Architecture du Modèle LSTM
    model = Sequential([
        # Couche LSTM qui comprend la temporalité
        LSTM(50, return_sequences=True, input_shape=(sequence_length, 5)),
        Dropout(0.2), # Évite le sur-apprentissage
        LSTM(50),
        Dropout(0.2),
        # Couche dense finale pour sortir 5 nombres
        Dense(5, activation='sigmoid') # Sigmoid car sortie entre 0 et 1
    ])

    model.compile(optimizer='adam', loss='mse')
    
    # 5. Entraînement Rapide (Fast-Training)
    # En prod réelle, on augmenterait epochs, mais ici on veut une réponse en < 5 sec
    model.fit(X, y, epochs=15, batch_size=16, verbose=0)

    # 6. Prédiction
    last_sequence = data_normalized[-sequence_length:]
    last_sequence = last_sequence.reshape((1, sequence_length, 5))
    
    prediction = model.predict(last_sequence, verbose=0)
    
    # Dénormalisation
    predicted_numbers = (prediction * 50.0).flatten()
    predicted_numbers = np.round(predicted_numbers).astype(int)
    
    # 7. Formatage de la sortie pour Java
    # On donne un score (poids) aux numéros prédits
    for num in predicted_numbers:
        val = int(num)
        if 1 <= val <= 49:
            # On assigne un poids fort (20.0) aux numéros vus par l'IA
            # Cela s'ajoutera aux scores statistiques (Forme, Ecart...)
            weights[str(val)] = 20.0
            
    return weights

if __name__ == "__main__":
    # Point d'entrée appelé par Java
    if len(sys.argv) < 2:
        print("{}") # JSON vide si erreur
        sys.exit(0)
        
    file_path = sys.argv[1]
    result = train_and_predict(file_path)
    # Seul ce print doit sortir sur stdout pour être lu par Java
    print(json.dumps(result))
