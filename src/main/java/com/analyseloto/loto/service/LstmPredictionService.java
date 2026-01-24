package com.analyseloto.loto.service;

import com.analyseloto.loto.entity.LotoTirage;
import lombok.extern.slf4j.Slf4j;
import org.deeplearning4j.nn.conf.MultiLayerConfiguration;
import org.deeplearning4j.nn.conf.NeuralNetConfiguration;
import org.deeplearning4j.nn.conf.layers.LSTM;
import org.deeplearning4j.nn.conf.layers.RnnOutputLayer;
import org.deeplearning4j.nn.multilayer.MultiLayerNetwork;
import org.deeplearning4j.nn.weights.WeightInit;
import org.nd4j.linalg.activations.Activation;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.dataset.DataSet;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.linalg.indexing.NDArrayIndex;
import org.nd4j.linalg.learning.config.Adam;
import org.nd4j.linalg.lossfunctions.LossFunctions;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class LstmPredictionService {

    private MultiLayerNetwork lstmModel;
    private static final int WINDOW_SIZE = 10; // On regarde les 10 derniers tirages pour prédire le 11ème
    private static final int NUM_FEATURES = 50; // 49 boules + index 0 vide

    public LstmPredictionService() {
        // Initialisation de l'architecture du Cerveau au démarrage
        this.lstmModel = buildLstmNetwork();
    }

    /**
     * Construction de l'architecture LSTM (Le Cerveau)
     */
    private MultiLayerNetwork buildLstmNetwork() {
        MultiLayerConfiguration conf = new NeuralNetConfiguration.Builder()
                .seed(12345)
                .weightInit(WeightInit.XAVIER)
                .updater(new Adam(0.005)) // Taux d'apprentissage
                .list()
                .layer(0, new LSTM.Builder()
                        .nIn(NUM_FEATURES) // Entrée : Vecteur binaire des 49 numéros
                        .nOut(128) // 128 neurones de mémoire (capacité cognitive)
                        .activation(Activation.TANH)
                        .dropOut(0.2) // Empêche l'overfitting (apprendre par cœur)
                        .build())
                .layer(1, new RnnOutputLayer.Builder(LossFunctions.LossFunction.MCXENT)
                        .nIn(128)
                        .nOut(NUM_FEATURES) // Sortie : Probabilité pour les 49 numéros
                        .activation(Activation.SOFTMAX) // Les probabilités feront 100% au total
                        .build())
                .build();

        MultiLayerNetwork net = new MultiLayerNetwork(conf);
        net.init();
        log.info("🧠 Réseau LSTM initialisé (128 unités).");
        return net;
    }

    /**
     * Entraînement asynchrone du modèle sur l'historique
     */
    public void trainModel(List<LotoTirage> historique) {
        log.info("🚀 Début de l'entraînement du réseau de neurones...");

        // On inverse pour avoir l'ordre chronologique (du plus vieux au plus récent)
        List<LotoTirage> chronoHistory = historique.stream()
                .sorted(Comparator.comparing(LotoTirage::getDateTirage))
                .toList();

        // Création du DataSet 3D pour le LSTM [Nombre d'exemples, Features (50), TimeSteps (10)]
        int numSamples = chronoHistory.size() - WINDOW_SIZE;
        if (numSamples < 1) return;

        INDArray input = Nd4j.zeros(numSamples, NUM_FEATURES, WINDOW_SIZE);
        INDArray labels = Nd4j.zeros(numSamples, NUM_FEATURES, WINDOW_SIZE);

        for (int i = 0; i < numSamples; i++) {
            for (int t = 0; t < WINDOW_SIZE; t++) {
                LotoTirage pastDraw = chronoHistory.get(i + t);
                LotoTirage targetDraw = chronoHistory.get(i + t + 1);

                // Encodage "One-Hot" : On met des '1' là où les boules sont sorties
                for (int boule : pastDraw.getBoules()) input.putScalar(new int[]{i, boule, t}, 1.0);
                for (int boule : targetDraw.getBoules()) labels.putScalar(new int[]{i, boule, t}, 1.0);
            }
        }

        // Entraînement brutal sur 10 époques (le réseau lit l'historique 10 fois)
        DataSet dataSet = new DataSet(input, labels);
        for (int epoch = 0; epoch < 10; epoch++) {
            lstmModel.fit(dataSet);
        }
        log.info("✅ Entraînement Deep Learning terminé.");
    }

    /**
     * Prédiction du prochain tirage
     * @return Map avec la boule en Clé et sa probabilité prédite par l'IA en Valeur
     */
    public Map<Integer, Double> getLstmPredictions(List<LotoTirage> recentHistory) {
        if (recentHistory.size() < WINDOW_SIZE) return new HashMap<>();

        // On prend les 10 derniers tirages
        INDArray input = Nd4j.zeros(1, NUM_FEATURES, WINDOW_SIZE);
        for (int t = 0; t < WINDOW_SIZE; t++) {
            LotoTirage pastDraw = recentHistory.get(WINDOW_SIZE - 1 - t);
            for (int boule : pastDraw.getBoules()) {
                input.putScalar(new int[]{0, boule, t}, 1.0);
            }
        }

        // LE MOMENT MAGIQUE : Feed Forward
        INDArray output = lstmModel.rnnTimeStep(input);

        // On utilise NDArrayIndex pour découper le tenseur 3D et récupérer le dernier pas de temps
        INDArray lastStepOutput = output.get(NDArrayIndex.point(0), NDArrayIndex.all(), NDArrayIndex.point(WINDOW_SIZE - 1));

        Map<Integer, Double> scoresLstm = new HashMap<>();
        for (int boule = 1; boule <= 49; boule++) {
            double proba = lastStepOutput.getDouble(boule);
            // On multiplie par 100 pour ramener sur une échelle de points compatible avec vos autres scores
            scoresLstm.put(boule, proba * 100.0);
        }

        return scoresLstm;
    }
}
