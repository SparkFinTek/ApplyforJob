package com.sparkinvesco.jobflow.services;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Component
public class PdfTextExtractor {

    /** Extract plain text from a PDF on disk. Empty string for unreadable / non-PDF files.
     *  Uses PDFBox 2.x API to stay compatible with openhtmltopdf-pdfbox 1.0.10. */
    public String extractText(Path pdf) throws IOException {
        if (pdf == null || !Files.exists(pdf)) return "";
        String name = pdf.getFileName().toString().toLowerCase();
        if (name.endsWith(".txt") || name.endsWith(".md")) {
            return Files.readString(pdf);
        }
        if (!name.endsWith(".pdf")) return "";
        try (PDDocument doc = PDDocument.load(pdf.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            return stripper.getText(doc);
        }
    }
}
