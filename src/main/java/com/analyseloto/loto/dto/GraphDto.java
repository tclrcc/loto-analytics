package com.analyseloto.loto.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

@Data
@AllArgsConstructor
public class GraphDto implements Serializable {
    @Serial private static final long serialVersionUID = 1L;

    private List<Node> nodes;
    private List<Edge> edges;

    @Data
    @AllArgsConstructor
    public static class Node implements Serializable {
        @Serial private static final long serialVersionUID = 1L;

        private int id;
        private String label;
        private int value; // Taille du point (Fréquence)
        private String color; // Rouge pour chance, Bleu pour normal
    }

    @Data
    @AllArgsConstructor
    public static class Edge implements Serializable {
        @Serial private static final long serialVersionUID = 1L;

        private int from;
        private int to;
        private int value; // Épaisseur du trait (Force du lien)
    }
}
