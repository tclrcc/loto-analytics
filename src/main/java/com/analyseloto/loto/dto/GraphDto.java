package com.analyseloto.loto.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import java.util.List;

@Data
@AllArgsConstructor
public class GraphDto {
    private List<Node> nodes;
    private List<Edge> edges;

    @Data
    @AllArgsConstructor
    public static class Node {
        private int id;
        private String label;
        private int value; // Taille du point (Fréquence)
        private String color; // Rouge pour chance, Bleu pour normal
    }

    @Data
    @AllArgsConstructor
    public static class Edge {
        private int from;
        private int to;
        private int value; // Épaisseur du trait (Force du lien)
    }
}