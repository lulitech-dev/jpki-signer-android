package dev.lulitech.jpkisigner.data

/**
 * The smallest thing PDFBox accepts as a one-page document.
 *
 * `:app` sees `:pdf` but not the PDF writer behind it, so a test that needs to
 * get past validation and into the signing path cannot build a document -- it
 * has to carry one. Anything the validator rejects short-circuits before the
 * card, which is exactly what most of these tests are trying to look past.
 */
internal val MINIMAL_PDF = (
    "%PDF-1.4\n" +
        "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n" +
        "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n" +
        "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] >>\nendobj\n" +
        "xref\n0 4\n" +
        "0000000000 65535 f \n" +
        "0000000009 00000 n \n" +
        "0000000058 00000 n \n" +
        "0000000115 00000 n \n" +
        "trailer\n<< /Size 4 /Root 1 0 R >>\nstartxref\n186\n%%EOF\n"
    ).toByteArray(Charsets.ISO_8859_1)
