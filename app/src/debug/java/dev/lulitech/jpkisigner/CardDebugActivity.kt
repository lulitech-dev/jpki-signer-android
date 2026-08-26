package dev.lulitech.jpkisigner

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.text.method.ScrollingMovementMethod
import android.util.TypedValue
import android.view.WindowManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.widget.doAfterTextChanged
import androidx.core.view.WindowInsetsCompat
import dev.lulitech.jpkisigner.jpki.DigestInfo
import dev.lulitech.jpkisigner.jpki.JpkiKey
import dev.lulitech.jpkisigner.jpki.JpkiSession
import dev.lulitech.jpkisigner.jpki.NfcCardReader
import dev.lulitech.jpkisigner.pdf.PdfSigner
import dev.lulitech.jpkisigner.pdf.SignParams
import dev.lulitech.jpkisigner.pdf.SignatureInspector
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.crypto.Cipher

/**
 * Card-layer bring-up screen. Temporary; the real UI replaces it.
 *
 * Read-only until a PIN is typed in. With a PIN present it performs exactly one
 * VERIFY and one signature per tap, and never retries -- if a card leaves the
 * field mid-VERIFY we cannot know whether the card counted the attempt, so a
 * retry could spend two on one user action.
 *
 * Defaults to the authentication key (4 digits, 3 attempts). The signature key
 * must be opted into explicitly, because a lockout there means a trip to a
 * municipal window.
 */
class CardDebugActivity : Activity() {

    private lateinit var output: TextView
    private lateinit var pinField: EditText
    private lateinit var useSignatureKey: CheckBox
    private lateinit var reader: NfcCardReader
    private val log = StringBuilder()

    /**
     * UI state mirrored for the NFC callback, which runs on a binder thread.
     *
     * View fields are not volatile, so reading `CheckBox.isChecked` or
     * `EditText.text` straight from that thread can see a stale value -- which
     * really happened, and made the app validate a signature PIN against the
     * authentication key's rules. Listeners publish here on the main thread and
     * the callback reads only these.
     */
    @Volatile
    private var signatureKeySelected = false

    @Volatile
    private var pinSnapshot: CharArray? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A PIN is on screen; keep it out of screenshots and the recents preview.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )

        pinField = EditText(this).apply {
            hint = "PIN (leave empty for read-only)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            doAfterTextChanged { text ->
                pinSnapshot?.fill(' ')
                pinSnapshot = text?.takeIf { it.isNotEmpty() }?.toString()?.toCharArray()
            }
        }
        useSignatureKey = CheckBox(this).apply {
            text = "Use signature key (5 attempts) instead of auth key (3)"
            setOnCheckedChangeListener { _, checked -> signatureKeySelected = checked }
        }
        output = TextView(this).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            movementMethod = ScrollingMovementMethod()
            typeface = Typeface.MONOSPACE
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(pinField)
            addView(useSignatureKey)
            addView(ScrollView(this@CardDebugActivity).apply { addView(output) })
        }
        // Android 15+ lays content out edge to edge, so without this the first
        // child sits underneath the status bar. Inset-driven rather than a magic
        // constant, since the real UI needs the same treatment.
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime(),
            )
            view.setPadding(32, bars.top + 32, 32, bars.bottom + 32)
            insets
        }
        setContentView(root)

        PDFBoxResourceLoader.init(applicationContext)

        reader = NfcCardReader(this)
        report(
            when {
                !reader.isAvailable -> "NFC not available on this device"
                !reader.isEnabled -> "NFC is off -- enable it in Settings"
                else -> "Type a PIN (or leave it empty), then hold the card to the phone."
            },
        )
    }

    override fun onResume() {
        super.onResume()
        if (reader.isEnabled) {
            reader.start(::run) { problem -> report("Card unusable: $problem") }
        }
    }

    override fun onPause() {
        super.onPause()
        reader.stop()
    }

    /** Runs on a binder thread while the card is in the field. */
    private fun run(session: JpkiSession) {
        val key = if (signatureKeySelected) JpkiKey.DIGITAL_SIGNATURE else JpkiKey.AUTHENTICATION
        val pin = pinSnapshot?.copyOf()
        val lines = mutableListOf<String>()

        try {
            session.selectApplication()
            lines += "key: $key"

            val before = session.remainingAttempts(key)
            lines += "attempts before: $before/${key.maxAttempts}"

            if (pin == null) {
                lines += "no PIN entered -- read-only, nothing spent"
                lines += describeCertificates(session, key, pinVerified = false)
                return
            }

            key.validatePin(pin)?.let {
                lines += "rejected locally, no APDU sent: $it"
                return
            }
            if (before <= 1) {
                lines += "REFUSING: only $before attempt(s) left"
                return
            }

            session.verifyPin(key, pin)
            lines += "VERIFY: ok (counter resets to ${key.maxAttempts})"

            lines += describeCertificates(session, key, pinVerified = true)
            lines += ""
            lines += signAndSelfCheck(session, key)
            lines += ""
            lines += signPdf(session, key)
        } catch (e: Exception) {
            lines += "ERROR ${e::class.java.simpleName}: ${e.message}"
        } finally {
            pin?.fill(' ')
            runOnUiThread {
                pinSnapshot?.fill(' ')
                pinSnapshot = null
                pinField.text.clear()
            }
            report(lines.joinToString("\n"))
        }
    }

    private fun describeCertificates(
        session: JpkiSession,
        key: JpkiKey,
        pinVerified: Boolean,
    ): String = buildString {
        appendLine("")
        appendLine("--- certificate (EF %04X)".format(key.certificateEf))
        runCatching { session.readCertificate(key) }
            .onSuccess { append(describe(it, redactSubject = key == JpkiKey.DIGITAL_SIGNATURE)) }
            .onFailure {
                append("read FAILED: ${it.message}")
                if (!pinVerified) append("  <- may require a verified PIN")
            }
        appendLine("")
        appendLine("")
        appendLine("--- CA certificate (EF %04X)".format(key.caCertificateEf))
        runCatching { session.readCaCertificate(key) }
            // A CA certificate names an authority, not a person: nothing to redact.
            .onSuccess { append(if (it == null) "absent" else describe(it)) }
            .onFailure { append("read FAILED: ${it.message}") }
    }.trimEnd()

    /**
     * Signs a fixed message and checks the result against the certificate's own
     * public key. If this passes, the whole chain works: VERIFY, key selection,
     * COMPUTE DIGITAL SIGNATURE, and DigestInfo construction.
     */
    private fun signAndSelfCheck(session: JpkiSession, key: JpkiKey): String = buildString {
        appendLine("--- sign + self-check")
        val message = "jpki-signer bring-up".toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(message)
        val digestInfo = DigestInfo.forSha256(digest)
        appendLine("digestInfo: ${digestInfo.size} bytes")

        val signature = session.signDigestInfo(key, digestInfo)
        appendLine("signature:  ${signature.size} bytes")

        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(session.readCertificate(key))) as X509Certificate
        val recovered = Cipher.getInstance("RSA/ECB/PKCS1Padding").run {
            init(Cipher.DECRYPT_MODE, cert.publicKey)
            doFinal(signature)
        }
        val ok = recovered.contentEquals(digestInfo)
        appendLine(if (ok) "VERIFIED against the card's own certificate" else "MISMATCH")
        if (!ok) {
            appendLine("expected: " + digestInfo.joinToString(" ") { "%02x".format(it) })
            appendLine("got:      " + recovered.joinToString(" ") { "%02x".format(it) })
        }
    }.trimEnd()

    /**
     * @param redactSubject the signature certificate's subject carries the holder's
     *   real name and address. Proving the read path works needs only the shape of
     *   the subject, not its contents, so it is summarised rather than printed.
     */
    /**
     * Signs a real PDF with the card, inside this one card session.
     *
     * Everything the CMS needs -- both certificates and the signature over the
     * signed attributes -- is fetched while the card is still in the field, so a
     * signing run is a single tap.
     */
    private fun signPdf(session: JpkiSession, key: JpkiKey): String = buildString {
        appendLine("--- sign a PDF")
        val dir = getExternalFilesDir(null)
        val original = File(dir, "original.pdf")
        val signed = File(dir, "signed-by-card.pdf")

        PDDocument().use { document ->
            val page = PDPage()
            document.addPage(page)
            PDPageContentStream(document, page).use { content ->
                content.beginText()
                content.setFont(PDType1Font.HELVETICA, 14f)
                content.newLineAtOffset(72f, 720f)
                content.showText("JPKI Signer - card signing test")
                content.newLineAtOffset(0f, -24f)
                content.showText("Signed with a My Number Card over NFC.")
                content.endText()
            }
            document.save(original)
        }
        appendLine("original: ${original.length()} bytes")

        PdfSigner(CardSignatureProvider(session, key)).sign(
            input = original,
            output = signed,
            params = SignParams(reason = "test signature", location = "Tokyo"),
        )
        appendLine("signed:   ${signed.length()} bytes")
        appendLine("path:     ${signed.absolutePath}")

        SignatureInspector.inspect(signed).forEachIndexed { i, info ->
            appendLine("signature #${i + 1}:")
            appendLine("  integrity           = ${info.integrity}")
            appendLine("  coversWholeDocument = ${info.coversWholeDocument}")
            appendLine("  signerCommonName    = <redacted>")
            info.verificationError?.let { appendLine("  error = $it") }
        }
    }.trimEnd()

    private fun describe(der: ByteArray, redactSubject: Boolean = false): String = buildString {
        appendLine("length: ${der.size} bytes")
        runCatching {
            val cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
            if (redactSubject) {
                val subject = cert.subjectX500Principal.name
                val rdns = subject.split(",").map { it.substringBefore("=").trim() }
                appendLine("subject: <redacted> (${subject.length} chars, RDNs: ${rdns.joinToString("+")})")
                appendLine("subject has CN: ${subject.contains("CN=")}")
            } else {
                appendLine("subject: ${cert.subjectX500Principal}")
            }
            appendLine("issuer:  ${cert.issuerX500Principal}")
            appendLine("valid:   ${cert.notBefore} .. ${cert.notAfter}")
        }.onFailure { appendLine("X.509 parse failed: ${it.message}") }
    }.trimEnd()

    /**
     * Mirrors results to logcat, because FLAG_SECURE blocks screencap and this
     * is the only way to read the outcome off-device during bring-up.
     *
     * Never logs the PIN. It does log certificate subjects, which for the
     * signature certificate include the holder's name and address -- acceptable
     * for a local bring-up screen, and a reason this class must not survive into
     * the real UI.
     */
    private fun report(text: String) {
        log.append(text).append("\n\n")
        android.util.Log.i(TAG, text)
        runOnUiThread { output.text = log.toString() }
    }

    private companion object {
        const val TAG = "JpkiBringUp"
    }
}
