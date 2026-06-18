package fr.vetbrain.stagevetmanager.scraper

import fr.vetbrain.stagevetmanager.model.ConventionPdfData
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper

/**
 * Extrait le texte d'un PDF de convention stagevet.fr via PDFBox, puis tente
 * de parser les champs usuels d'une convention de stage française.
 *
 * Les regex sont adaptées au format courant de stagevet.fr mais peuvent nécessiter
 * un affinage une fois les vrais PDFs observés. La propriété [ConventionPdfData.rawText]
 * contient le texte intégral pour faciliter ce travail.
 *
 * POUR AFFINER : lancer l'appli, cliquer sur l'icône 🔍 d'une convention,
 * copier rawText depuis la boîte de dialogue, puis adapter les regex ci-dessous.
 */
object ConventionPdfParser {

    fun parse(pdfBytes: ByteArray, sourceUrl: String = ""): ConventionPdfData {
        val text = extractText(pdfBytes)
        return ConventionPdfData(
            rawText        = text,
            sourceUrl      = sourceUrl,
            conventionNumber  = find(text, Regex("""[Cc]onvention\s+n[°o]?\s*[:\s]+([A-Z0-9\-/]+)""")),
            studentName       = find(text, Regex("""[Ss]tagiaire\s*[:\-–]\s*([A-ZÀ-Ÿ][a-zà-ÿ]+(?:\s+[A-ZÀ-Ÿ][a-zà-ÿ]+)+)""")),
            studentBirthDate  = find(text, Regex("""[Nn]é\(?e?\)?\s+le\s*[:\-–]?\s*(\d{2}[/\-]\d{2}[/\-]\d{4})""")),
            studentAddress    = find(text, Regex("""[Aa]dresse\s+du\s+stagiaire\s*[:\-–]?\s*(.{5,120})""")),
            schoolTutor       = find(text, Regex("""(?:[Tt]uteur|[Rr]éférent\s+pédagogique|[Ee]nseignant\s+référent)\s*[:\-–]?\s*(.{3,80})""")),
            hostOrganization  = find(text, Regex("""(?:[Oo]rganisme|[Ee]ntreprise|[Ss]tructure)\s+d'accueil\s*[:\-–]?\s*(.{3,120})""")),
            hostSiret         = find(text, Regex("""SIRET\s*[:\-–]?\s*(\d[\d\s]{12,16})""")),
            hostAddress       = find(text, Regex("""[Aa]dresse\s+(?:de\s+l'organisme|de\s+la\s+structure|de\s+l'entreprise)\s*[:\-–]?\s*(.{5,120})""")),
            supervisorName    = find(text, Regex("""[Mm]aître\s+de\s+stage\s*[:\-–]?\s*([A-ZÀ-Ÿ][a-zà-ÿ]+(?:\s+[A-ZÀ-Ÿ][a-zà-ÿ]+)+)""")),
            supervisorTitle   = find(text, Regex("""[Ff]onction\s+du\s+(?:maître\s+de\s+stage|tuteur)\s*[:\-–]?\s*(.{3,80})""")),
            startDate         = find(text, Regex("""[Dd]ébut\s+(?:du\s+stage)?\s*[:\-–]?\s*(\d{2}[/\-]\d{2}[/\-]\d{4})""")),
            endDate           = find(text, Regex("""[Ff]in\s+(?:du\s+stage)?\s*[:\-–]?\s*(\d{2}[/\-]\d{2}[/\-]\d{4})""")),
            duration          = find(text, Regex("""[Dd]urée\s+(?:totale\s+)?(?:du\s+stage)?\s*[:\-–]?\s*(.{1,60})""")),
            gratification     = find(text, Regex("""[Gg]ratification\s*[:\-–]?\s*(.{1,120})""")),
            objectives        = find(text, Regex("""[Oo]bjectifs?\s+(?:pédagogiques?\s+)?(?:du\s+stage\s+)?[:\-–]?\s*(.{10,600})""")),
        )
    }

    private fun extractText(bytes: ByteArray): String =
        Loader.loadPDF(bytes).use { doc -> PDFTextStripper().getText(doc) }

    private fun find(text: String, pattern: Regex): String =
        pattern.find(text)?.groupValues?.getOrNull(1)?.trim()?.take(500) ?: ""
}
