package com.analyseloto.loto.service;

import com.analyseloto.loto.entity.UserBet;
import com.lowagie.text.*;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class PdfService {

    /**
     * Création du fichier PDF contenant les grilles de l'utilisateur
     * @param bets liste des grilles
     * @param prenom prénom utilisateur
     * @return
     * @throws IOException
     */
    public byte[] generateBetPdf(List<UserBet> bets, String prenom) throws IOException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4);
            PdfWriter.getInstance(document, out);

            document.open();

            // 1. Titre
            Font fontTitle = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 20, new Color(79, 70, 229)); // Violet
            Paragraph title = new Paragraph("Feuille de Jeu - Loto Master AI", fontTitle);
            title.setAlignment(Element.ALIGN_CENTER);
            document.add(title);

            document.add(new Paragraph(" ")); // Espace

            // 2. Info Joueur
            Font fontSub = FontFactory.getFont(FontFactory.HELVETICA, 12, Color.GRAY);
            Paragraph sub = new Paragraph("Joueur : " + prenom + " | Grilles à valider", fontSub);
            sub.setAlignment(Element.ALIGN_CENTER);
            document.add(sub);

            document.add(new Paragraph(" "));
            document.add(new Paragraph(" "));

            // 3. Tableau des grilles
            PdfPTable table = new PdfPTable(4); // 4 Colonnes : Date, Numéros, Chance, Mise
            table.setWidthPercentage(100);
            table.setWidths(new float[]{3, 6, 2, 2});

            // En-têtes
            addHeader(table, "Date Tirage");
            addHeader(table, "Numéros");
            addHeader(table, "Chance");
            addHeader(table, "Mise");

            // Données
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy");
            Font fontBold = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12);
            Font fontNormal = FontFactory.getFont(FontFactory.HELVETICA, 12);

            for (UserBet bet : bets) {
                // Date
                table.addCell(new PdfPCell(new Phrase(bet.getDateJeu().format(fmt), fontNormal)));

                // Numéros (Formatés proprement)
                String nums = String.format("%d - %d - %d - %d - %d",
                        bet.getB1(), bet.getB2(), bet.getB3(), bet.getB4(), bet.getB5());
                PdfPCell cellNum = new PdfPCell(new Phrase(nums, fontBold));
                cellNum.setHorizontalAlignment(Element.ALIGN_CENTER);
                cellNum.setPadding(10);
                table.addCell(cellNum);

                // Chance (En rouge)
                Font fontChance = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, Color.RED);
                PdfPCell cellChance = new PdfPCell(new Phrase(String.valueOf(bet.getChance()), fontChance));
                cellChance.setHorizontalAlignment(Element.ALIGN_CENTER);
                cellChance.setPadding(10);
                table.addCell(cellChance);

                // Mise
                table.addCell(new PdfPCell(new Phrase(bet.getMise() + " €", fontNormal)));
            }

            document.add(table);

            // 4. Footer
            document.add(new Paragraph(" "));
            Font fontFooter = FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 10, Color.GRAY);
            Paragraph footer = new Paragraph("Généré automatiquement par Loto Master AI. Jeu interdit aux mineurs.", fontFooter);
            footer.setAlignment(Element.ALIGN_CENTER);
            document.add(footer);

            document.close();
            return out.toByteArray();
        }
    }

    private void addHeader(PdfPTable table, String text) {
        PdfPCell header = new PdfPCell();
        header.setBackgroundColor(new Color(243, 244, 246));
        header.setPadding(10);
        header.setPhrase(new Phrase(text, FontFactory.getFont(FontFactory.HELVETICA_BOLD)));
        header.setHorizontalAlignment(Element.ALIGN_CENTER);
        table.addCell(header);
    }
}
