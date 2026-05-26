package com.sparkinvesco.jobflow.services;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Calls the Anthropic API to tailor the base resume to a job description.
 *
 * Truthfulness rules — non-negotiable, baked into the system prompt:
 *   • Employers, titles, dates, degrees, certifications: copied verbatim.
 *   • Numbers (40%, $100M, etc.): copied verbatim.
 *   • Skills only appear if they appear in the base resume.
 *   • No invented experience anywhere.
 *
 * The model returns a complete styled HTML document for the resume, which
 * PdfRenderService then converts to PDF.
 */
@Service
public class TailoringService {

    private final AnthropicClient anthropic;
    private final PdfTextExtractor pdfText;

    public TailoringService(AnthropicClient anthropic, PdfTextExtractor pdfText) {
        this.anthropic = anthropic;
        this.pdfText = pdfText;
    }

    public boolean isAvailable() {
        return anthropic.isConfigured();
    }

    /**
     * Returns a fully styled HTML document for the tailored resume.
     * Throws IllegalStateException if the API key isn't configured.
     */
    public String tailorToHtml(Path baseResume, String jdText, Map<String, Object> ownerInfo) throws IOException {
        String baseText = pdfText.extractText(baseResume);
        if (baseText.isBlank()) {
            throw new IOException("Could not read base resume text from " + baseResume);
        }
        String response = anthropic.message(SYSTEM_PROMPT, buildUserPrompt(baseText, jdText, ownerInfo));
        return extractHtml(response);
    }

    private String buildUserPrompt(String baseResumeText, String jdText, Map<String, Object> ownerInfo) {
        return """
                Below is a base resume (BASE_RESUME) and a job description (JOB_DESCRIPTION).

                Produce a tailored version of the resume specifically for this posting.
                Apply ALL the truthfulness, layout, and CSS rules in the system prompt.

                IMPORTANT — copy the contact line (name, location, phone, email, LinkedIn)
                EXACTLY as it appears at the top of the BASE_RESUME. Do NOT substitute
                a different email or phone — whatever is in the base resume is what
                must appear on the tailored resume.

                BASE_RESUME:
                ---
                %s
                ---

                JOB_DESCRIPTION:
                ---
                %s
                ---

                Return ONE complete HTML document. Start the response with <!DOCTYPE html>.
                Do NOT wrap the HTML in markdown code fences.
                """.formatted(
                baseResumeText.strip(),
                jdText.strip()
        );
    }

    /**
     * If the model returned the HTML inside ```html fences (despite the instruction),
     * strip them. Otherwise return as-is.
     */
    private String extractHtml(String response) {
        String s = response.strip();
        if (s.startsWith("```")) {
            int firstNewline = s.indexOf('\n');
            if (firstNewline > 0) s = s.substring(firstNewline + 1);
            if (s.endsWith("```")) s = s.substring(0, s.length() - 3).strip();
        }
        return s;
    }

    private static String strOrEmpty(Object v) {
        return v == null ? "" : v.toString();
    }

    private static final String SYSTEM_PROMPT = """
            You tailor resumes for senior engineering executives applying to specific job postings.

            === ABSOLUTE TRUTHFULNESS RULES (non-negotiable) ===
            1. Copy employers, job titles, dates of employment, degrees, schools,
               certifications, and named projects VERBATIM from the base resume.
            2. Copy numeric outcomes verbatim (40%, $100M, 30% YoY, etc.).
            3. A skill appears only if it appears in the base resume. Do not invent.
            4. Voice and pronouns match the base resume.

            === ALLOWED TAILORING ===
            - Reorder bullets so the most relevant experience appears first.
            - Reword existing bullets to use the JD's vocabulary IF still truthful.
            - Drop or de-emphasize bullets the JD doesn't care about.
            - Adjust the subtitle line and executive summary to highlight relevant
              strands.

            === OUTPUT FORMAT — RETURN EXACTLY THIS HTML SHAPE ===
            Do not change the CSS, class names, or structural skeleton. Fill in
            ONLY the bracketed [content] placeholders. Use exactly 6 KPI tiles in
            one row, exactly 4 capability boxes (visual 2x2), and one experience
            block per company. Render with openhtmltopdf-friendly CSS — no flex,
            no grid (the template uses inline-block + float, which works).

            <!DOCTYPE html>
            <html>
            <head><meta charset="UTF-8"><style>
            @page { size: letter; margin: 0.55in 0.7in; }
            body { font: 10.5pt/1.45 'Calibri','Helvetica',sans-serif; color: #222; margin: 0; }
            h1.name { font-size: 22pt; font-weight: 700; text-align: center; color: #1a1a1a; letter-spacing: 0.02em; margin: 0; }
            p.subtitle { font-size: 10.5pt; text-align: center; color: #444; font-style: italic; margin: 4px 0 6px; }
            p.contact { font-size: 9.5pt; text-align: center; color: #555; margin: 0 0 14px; }
            .kpi-ribbon { font-size: 0; text-align: center; margin: 0 0 16px; padding: 8px 0; border-top: 1px solid #d0d0d0; border-bottom: 1px solid #d0d0d0; }
            .kpi { display: inline-block; width: 16.4%; vertical-align: top; padding: 0 4px; box-sizing: border-box; }
            .kpi .num { font-size: 14pt; font-weight: 700; color: #1f4e79; display: block; line-height: 1.1; }
            .kpi .lbl { font-size: 8pt; color: #555; line-height: 1.25; display: block; margin-top: 3px; }
            h2 { font-size: 10.5pt; font-weight: 700; color: #1f4e79; text-transform: uppercase; letter-spacing: 0.06em; border-bottom: 1px solid #1f4e79; padding-bottom: 2px; margin: 14px 0 8px; }
            p.summary { margin: 0 0 10px; font-size: 9.5pt; line-height: 1.5; text-align: justify; }
            .caps { font-size: 0; margin: 0 0 8px; }
            .cap { display: inline-block; width: 49.5%; vertical-align: top; box-sizing: border-box; padding: 0 12px 8px 0; }
            .cap-title { font-weight: 700; color: #1f4e79; font-size: 9.5pt; margin: 0 0 2px; }
            .cap-body { font-size: 9pt; color: #333; line-height: 1.4; }
            .exp { margin: 0 0 10px; }
            .exp-header { overflow: hidden; }
            .exp-company { float: left; font-weight: 700; font-size: 10.5pt; color: #222; }
            .exp-meta { float: right; font-size: 9.5pt; color: #555; }
            .exp-role { font-size: 9.5pt; color: #333; font-style: italic; margin: 1px 0 3px; clear: both; }
            ul.bullets { margin: 3px 0 0 16px; padding: 0; }
            ul.bullets li { margin: 0 0 3px; font-size: 9pt; line-height: 1.4; }
            .edu { font-size: 9.5pt; line-height: 1.5; }
            </style></head>
            <body>

            <h1 class="name">[FULL NAME, all caps OK]</h1>
            <p class="subtitle">[Tailored 1-line subtitle reflecting the JD's emphasis. e.g. "Engineering Executive · Engineering Operations · AI-Augmented Developer Experience"]</p>
            <p class="contact">[City, ST] | [Phone] | [Email] | [LinkedIn URL]</p>

            <div class="kpi-ribbon">
              <div class="kpi"><span class="num">[NUM1]</span><span class="lbl">[short label]</span></div>
              <div class="kpi"><span class="num">[NUM2]</span><span class="lbl">[short label]</span></div>
              <div class="kpi"><span class="num">[NUM3]</span><span class="lbl">[short label]</span></div>
              <div class="kpi"><span class="num">[NUM4]</span><span class="lbl">[short label]</span></div>
              <div class="kpi"><span class="num">[NUM5]</span><span class="lbl">[short label]</span></div>
              <div class="kpi"><span class="num">[NUM6]</span><span class="lbl">[short label]</span></div>
            </div>

            <h2>Executive Summary</h2>
            <p class="summary">[3–5 sentence narrative. Lead with what the JD cares about. All factual.]</p>

            <h2>Core Capabilities — [TAILORED PHRASE]</h2>
            <div class="caps">
              <div class="cap"><div class="cap-title">[Capability 1]</div><div class="cap-body">[items separated by · ]</div></div>
              <div class="cap"><div class="cap-title">[Capability 2]</div><div class="cap-body">[items]</div></div>
              <div class="cap"><div class="cap-title">[Capability 3]</div><div class="cap-body">[items]</div></div>
              <div class="cap"><div class="cap-title">[Capability 4]</div><div class="cap-body">[items]</div></div>
            </div>

            <h2>Professional Experience</h2>
            <div class="exp">
              <div class="exp-header">
                <span class="exp-company">[Employer name]</span>
                <span class="exp-meta">[Location · Year–Year]</span>
              </div>
              <p class="exp-role">[Role title · subtitle]</p>
              <ul class="bullets">
                <li>[bullet]</li>
                <li>[bullet]</li>
              </ul>
            </div>
            <!-- Repeat .exp for each employer in the base resume, in reverse-chronological order. -->

            <h2>Education</h2>
            <div class="edu">
              [Degree] · [School]<br>
              [Other degree] · [Other school]
            </div>

            </body></html>

            === ABSOLUTE RULES ===
            - Use 6 KPI tiles, copied verbatim from the most impactful numbers in the
              base. Short label (4-6 words max) under each.
            - 4 capability boxes — visual 2x2 grid. Tailored to the JD's vocabulary.
            - All employers and dates VERBATIM. Re-order/reword bullets only.
            - No watermarks, no "generated by" lines, no markdown fences.
            - Return ONLY the HTML document, starting with <!DOCTYPE html>.
            """;
}
