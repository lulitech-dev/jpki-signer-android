package dev.lulitech.jpkisigner.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Prints [SignatureReport] for signatures this app produced, and for any file it
 * is pointed at -- so one made by another implementation can be laid beside ours.
 *
 * A verifier we do not have (the 法務省 PDF署名プラグイン) reports "the signature
 * part is in error" and nothing more. There is no way to ask it which field it
 * disliked, so the way in is to diff a signature it rejected against one it
 * accepts, and read off what differs.
 *
 * ```sh
 * # ours only, written to pdf/build/reports/signature/
 * ./gradlew :pdf:testDebugUnitTest --tests '*SignatureReportTest*'
 *
 * # ours against a real one, or two real ones against each other
 * ./gradlew :pdf:testDebugUnitTest --tests '*SignatureReportTest*' \
 *   -Pcompare=/path/rejected.pdf,/path/accepted.pdf
 * ```
 *
 * With `-Pverbose=true` the values that differ between any two signatures by
 * nature -- digests, signature bytes, serials, dates -- are printed too. They are
 * off by default so a diff shows only what is *structurally* different.
 *
 * Nothing here asserts anything about a file it was given: it is a diagnostic, and
 * a real document that fails to parse is a finding, not a broken build.
 */
class SignatureReportTest {

    @Test
    fun `report on a signature this app produced`() {
        val ours = signSample("ours.pdf", SignParams(reason = "動作確認", location = "東京"))
        val report = SignatureReport.of(ours, verbose = verbose)

        write("ours.txt", report)
        println("\n### report: ${outputDir.resolve("ours.txt").absolutePath}\n")
        println(report)

        // The properties a reader of this report should not have to re-check.
        assertTrue("must be a detached PKCS#7", report.contains("adbe.pkcs7.detached"))
        assertTrue(
            "BER, matching jpki-pdf-signer byte shape; DESIGN.md 5.1a",
            report.contains("indefiniteLength = true"),
        )
        assertTrue("a detached signature must not embed the content", report.contains("eContentPresent = false"))
    }

    /**
     * Two signatures produced by this app, one carrying a CA certificate and one
     * not, because that is the one part of the card layer DESIGN.md still marks as
     * unconfirmed -- and an extra or wrong certificate in the bag is exactly the
     * kind of thing a strict verifier refuses while every offline check passes.
     */
    @Test
    fun `report the difference a ca certificate makes`() {
        val withoutCa = signSample("without-ca.pdf", SignParams(reason = "no-ca"))
        val withCa = signSample(
            "with-ca.pdf",
            SignParams(reason = "with-ca"),
            provider = ProviderWithCa(),
        )

        val diff = ReportDiff.of(
            "without-ca", SignatureReport.of(withoutCa, verbose = verbose),
            "with-ca", SignatureReport.of(withCa, verbose = verbose),
        )
        write("ca-certificate.diff.txt", diff)
        println("\n### what a second certificate in the bag changes\n")
        println(diff)
    }

    /** Reports and diffs whatever `-Pcompare=a.pdf,b.pdf` names. */
    @Test
    fun `report on the files this run was pointed at`() {
        val paths = System.getProperty(COMPARE_PROPERTY).orEmpty()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (paths.isEmpty()) {
            println(
                "\n### no files given; pass -Pcompare=/path/a.pdf,/path/b.pdf " +
                    "to report on real signatures\n",
            )
            return
        }

        val reports = paths.map { path ->
            val file = File(path)
            val name = file.name
            if (!file.isFile) {
                println("### $path is not a file")
                return@map name to null
            }
            val report = runCatching { SignatureReport.of(file, verbose = verbose) }
                .getOrElse { "report.error = ${it::class.java.simpleName}: ${it.message}\n" }
            write("$name.txt", report)
            println("\n### report for $path\n")
            println(report)
            name to report
        }

        // Every pair, because three files is a perfectly reasonable thing to
        // compare: ours, one that was rejected, and one that was accepted.
        val usable = reports.filter { it.second != null }
        for (i in usable.indices) {
            for (j in i + 1 until usable.size) {
                val (leftName, left) = usable[i]
                val (rightName, right) = usable[j]
                val diff = ReportDiff.of(leftName, left!!, rightName, right!!)
                write("$leftName-vs-$rightName.diff.txt", diff)
                println("\n### $leftName vs $rightName\n")
                println(diff)
            }
        }
    }

    // --- fixtures ------------------------------------------------------------

    /**
     * A card also hands over the issuing CA certificate, which the fixture
     * provider has none of. This adds a second, distinct certificate to the bag so
     * the report shows what that alone changes.
     */
    private class ProviderWithCa(
        private val signer: SoftwareSignatureProvider = SoftwareSignatureProvider(),
        private val ca: SoftwareSignatureProvider = SoftwareSignatureProvider(commonName = "Test CA"),
    ) : SignatureProvider {
        override fun signerCertificate(): ByteArray = signer.signerCertificate()
        override fun caCertificate(): ByteArray = ca.signerCertificate()
        override fun signDigestInfo(digestInfo: ByteArray): ByteArray =
            signer.signDigestInfo(digestInfo)
    }

    private fun signSample(
        name: String,
        params: SignParams,
        provider: SignatureProvider = SoftwareSignatureProvider(),
    ): File {
        val original = File(outputDir, "original-$name")
        PDDocument().use { document ->
            document.addPage(PDPage())
            document.save(original)
        }
        val signed = File(outputDir, name)
        PdfSigner(provider).sign(original, signed, params)
        return signed
    }

    private fun write(name: String, text: String) {
        File(outputDir, name).writeText(text)
    }

    private companion object {
        const val COMPARE_PROPERTY = "jpki.compare"

        val outputDir: File = File("build/reports/signature").apply { mkdirs() }

        val verbose: Boolean = System.getProperty("jpki.verbose").toBoolean()
    }
}
