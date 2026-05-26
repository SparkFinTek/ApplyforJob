package com.sparkinvesco.jobflow.services;

import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.jsoup.Jsoup;
import org.jsoup.helper.W3CDom;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Render tailored resume HTML to a clean PDF with neutral metadata.
 * Uses openhtmltopdf which embeds via PDFBox under the hood.
 *
 * Neutral-metadata rule: the PDF's producer/creator fields are intentionally
 * generic. No "Claude" or "AI" tags. Recruiters get a clean PDF that doesn't
 * advertise its origin.
 */
@Service
public class PdfRenderService {

    public Path renderToFile(String html, Path outPdf, String authorName) throws IOException {
        Files.createDirectories(outPdf.getParent());
        Path tmp = outPdf.resolveSibling(outPdf.getFileName().toString() + ".tmp");

        Document jsoup = Jsoup.parse(html);
        jsoup.outputSettings().syntax(Document.OutputSettings.Syntax.xml);
        org.w3c.dom.Document w3c = new W3CDom().fromJsoup(jsoup);

        try (OutputStream os = Files.newOutputStream(tmp)) {
            PdfRendererBuilder builder = new PdfRendererBuilder();
            builder.useFastMode();
            builder.withW3cDocument(w3c, "");
            builder.toStream(os);
            builder.run();
        }

        // openhtmltopdf sets producer to "openhtmltopdf"; that's neutral
        // (and not AI-flavored), which is exactly what we want.
        Files.move(tmp, outPdf,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        return outPdf;
    }
}
