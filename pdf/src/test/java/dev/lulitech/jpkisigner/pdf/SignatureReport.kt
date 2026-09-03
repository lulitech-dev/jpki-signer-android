package dev.lulitech.jpkisigner.pdf

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.digitalsignature.PDSignature
import org.bouncycastle.asn1.ASN1Encoding
import org.bouncycastle.asn1.ASN1InputStream
import org.bouncycastle.asn1.ASN1Integer
import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.ASN1OctetString
import org.bouncycastle.asn1.ASN1Primitive
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.ASN1Set
import org.bouncycastle.asn1.ASN1String
import org.bouncycastle.asn1.ASN1UTCTime
import org.bouncycastle.asn1.DERNull
import org.bouncycastle.asn1.cms.Attribute
import org.bouncycastle.asn1.cms.ContentInfo
import org.bouncycastle.asn1.cms.IssuerAndSerialNumber
import org.bouncycastle.asn1.cms.SignedData
import org.bouncycastle.asn1.cms.SignerInfo
import org.bouncycastle.asn1.x509.AlgorithmIdentifier
import org.bouncycastle.asn1.x509.Certificate
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.X509CertificateHolder
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Locale

/**
 * A line-oriented, diffable description of every PDF signature in a file.
 *
 * Exists to answer one question: what does a signature this app produced carry
 * that a signature some other implementation produced does not, or the other way
 * round. A rejection from a verifier we do not have -- the 法務省 plugin, say --
 * says nothing about *which* field it disliked, and there is no way to ask it. So
 * the two signatures are laid out side by side and the difference is read off.
 *
 * Deliberately not a verifier. [SignatureInspector] already answers "does this
 * match the bytes it covers", and a signature can pass that and still be refused
 * on structure, on an attribute a profile requires, or on an algorithm identifier
 * spelled the legal-but-unexpected way. Those are what this prints.
 *
 * Every value is rendered stably -- sorted where order does not matter, and with
 * anything that changes per signature (the signature bytes, the digest, dates)
 * reduced to a length or a shape unless [verbose]. Two runs over the same file
 * give the same text, and two different files differ only where they really do.
 *
 * ### What the reference implementation emits
 *
 * Measured 2026-09-03, not recalled: `JPKISignatureInterface.sign()` was read out
 * of `jpki-wrapper-internal64.jar` (bundled in jpki-pdf-signer, BouncyCastle
 * **1.72** per its `build.gradle`) and its call sequence replayed against that same
 * BouncyCastle with a software key, so only the construction varied.
 *
 * Identical to ours, and therefore ruled out as a cause of any rejection:
 *
 *  - signed attributes: contentType, signingTime, **cmsAlgorithmProtect**,
 *    messageDigest -- the same four, in the same order. BC has added
 *    `cmsAlgorithmProtect` since well before 1.72, so it is not something newer
 *    BouncyCastle introduced on our side.
 *  - `SignerInfo.signatureAlgorithm` = `sha256WithRSAEncryption` + NULL, from
 *    `DefaultSignatureAlgorithmIdentifierFinder().find("SHA256withRSA")`.
 *  - `digestAlgorithm` = sha256 with **absent** parameters, per RFC 5754.
 *  - `SignerIdentifier` = issuerAndSerialNumber; detached, `eContent` absent.
 *  - the middleware hashes the signedAttrs with SHA-256 (`cryptCreateHash`
 *    `CALG_SHA_256`) and signs a PKCS#1 v1.5 DigestInfo, which is exactly what
 *    the card's COMPUTE DIGITAL SIGNATURE does.
 *
 * Two things differ, and both are what this report exists to show:
 *
 *  1. **Encoding.** The reference calls `CMSSignedData.getEncoded()` with no
 *     argument and lands on **BER**: `30 80`, four indefinite-length segments,
 *     EOC terminated. We call `getEncoded(ASN1Encoding.DER)` and emit `30 82`,
 *     definite length. DESIGN.md 5.1a argues for DER and the PDF spec requires it
 *     for `adbe.pkcs7.detached` -- but the accepted implementation is the BER one,
 *     so this is a real difference rather than a settled question.
 *  2. **The certificate bag.** The reference embeds exactly two, always:
 *     `getCertificate()` and `getRootCertificate()`. We embed the signer plus
 *     whatever `JpkiKey.caCertificateEf` reads, and `readCaCertificate` answers
 *     null for a missing EF -- which would leave a verifier one certificate to
 *     build a path from. That EF is the one part of the card layer DESIGN.md still
 *     marks unconfirmed, so [SignatureReport] prints the subject and issuer of
 *     every certificate in the bag.
 */
object SignatureReport {

    /**
     * @param verbose include values that differ between any two signatures by
     *   nature -- digests, signature bytes, serial numbers, timestamps. Off by
     *   default so a diff shows only what is structurally different.
     */
    fun of(file: File, verbose: Boolean = false): String {
        val bytes = file.readBytes()
        val out = Report()
        out.line("file.name", file.name)
        out.line("file.bytes", bytes.size)

        val signatures = try {
            PDDocument.load(bytes).use { document ->
                out.line("pdf.version", document.version)
                out.line("pdf.pages", document.numberOfPages)
                out.line("pdf.encrypted", document.isEncrypted)
                out.line("pdf.docmdp", DocMdp.existingPermission(document))
                document.signatureDictionaries.sortedBy(::revisionEndOf)
            }
        } catch (e: Exception) {
            out.line("pdf.error", "${e::class.java.simpleName}: ${e.message}")
            return out.toString()
        }

        out.line("pdf.signatures", signatures.size)
        signatures.forEachIndexed { i, sig -> describeSignature(out, "sig[$i]", sig, bytes, verbose) }
        return out.toString()
    }

    private fun describeSignature(
        out: Report,
        prefix: String,
        sig: PDSignature,
        bytes: ByteArray,
        verbose: Boolean,
    ) {
        val dict = sig.cosObject
        out.line("$prefix.dict.Type", dict.getNameAsString("Type") ?: ABSENT)
        out.line("$prefix.dict.Filter", sig.filter ?: ABSENT)
        out.line("$prefix.dict.SubFilter", sig.subFilter ?: ABSENT)
        out.line("$prefix.dict.Name", present(sig.name))
        out.line("$prefix.dict.Reason", present(sig.reason))
        out.line("$prefix.dict.Location", present(sig.location))
        out.line("$prefix.dict.ContactInfo", present(sig.contactInfo))
        out.line("$prefix.dict.M", if (sig.signDate == null) ABSENT else PRESENT)
        // /Cert is for adbe.x509.rsa_sha1 only; carrying it with a PKCS#7
        // SubFilter is a common way to confuse a strict verifier.
        out.line("$prefix.dict.Cert", if (dict.getDictionaryObject("Cert") == null) ABSENT else PRESENT)
        out.line("$prefix.dict.Changes", if (dict.getDictionaryObject("Changes") == null) ABSENT else PRESENT)
        out.line("$prefix.dict.Reference", if (dict.getDictionaryObject("Reference") == null) ABSENT else PRESENT)
        out.line("$prefix.dict.keys", dict.keySet().map { it.name }.sorted().joinToString(","))

        val byteRange = sig.byteRange
        out.line("$prefix.byteRange.entries", byteRange.size)
        out.line(
            "$prefix.byteRange.coversToEndOfFile",
            byteRange.size >= 4 && byteRange[2].toLong() + byteRange[3] == bytes.size.toLong(),
        )
        out.line("$prefix.byteRange.startsAtZero", byteRange.isNotEmpty() && byteRange[0] == 0)
        if (verbose) out.line("$prefix.byteRange", byteRange.joinToString(" "))

        val contents = try {
            sig.getContents(bytes)
        } catch (e: Exception) {
            out.line("$prefix.contents.error", "${e::class.java.simpleName}: ${e.message}")
            return
        }
        out.line("$prefix.contents.reservedBytes", contents.size)
        // The BER-vs-DER trap: a streamed CMSSignedDataGenerator emits an
        // indefinite-length SEQUENCE (30 80) which PDFBox's zero padding then
        // turns into a sea of end-of-contents octets. See DESIGN.md §5.1a.
        out.line("$prefix.contents.header", contents.take(2).toHex())
        out.line("$prefix.contents.indefiniteLength", contents.size > 1 && contents[1] == 0x80.toByte())

        val parsed = try {
            ASN1InputStream(ByteArrayInputStream(contents)).use { it.readObject() }
        } catch (e: Exception) {
            out.line("$prefix.cms.parseError", "${e::class.java.simpleName}: ${e.message}")
            return
        }
        out.line("$prefix.contents.derBytes", parsed.getEncoded(ASN1Encoding.DER).size)
        out.line("$prefix.contents.isCanonicalDer", parsed.isCanonicalDer(contents))

        describeCms(out, "$prefix.cms", parsed, verbose)

        // What our own verifier makes of it, so a structural difference can be
        // told apart from a signature that simply does not match its bytes.
        val integrity = runCatching { SignatureInspector.inspect(bytes) }.getOrNull()
        val info = integrity?.firstOrNull { it.reason == sig.reason && it.name == sig.name }
        out.line("$prefix.localVerdict", info?.integrity?.name ?: "NOT_CHECKED_HERE")
    }

    private fun describeCms(out: Report, prefix: String, parsed: ASN1Primitive, verbose: Boolean) {
        val contentInfo = try {
            ContentInfo.getInstance(parsed)
        } catch (e: Exception) {
            out.line("$prefix.error", "not a ContentInfo: ${e.message}")
            return
        }
        out.line("$prefix.contentType", oid(contentInfo.contentType))

        val signedData = try {
            SignedData.getInstance(contentInfo.content)
        } catch (e: Exception) {
            out.line("$prefix.error", "not a SignedData: ${e.message}")
            return
        }

        out.line("$prefix.version", signedData.version.value)
        out.line(
            "$prefix.digestAlgorithms",
            signedData.digestAlgorithms.toArray()
                .map { algorithm(AlgorithmIdentifier.getInstance(it)) }
                .sorted()
                .joinToString(","),
        )
        out.line("$prefix.eContentType", oid(signedData.encapContentInfo.contentType))
        // A detached signature must not carry the content. Embedding it is legal
        // CMS and wrong for adbe.pkcs7.detached.
        out.line(
            "$prefix.eContentPresent",
            signedData.encapContentInfo.content != null,
        )

        val certificates = signedData.certificates?.toArray()?.toList().orEmpty()
        out.line("$prefix.certificates", certificates.size)
        certificates
            .map { X509CertificateHolder(Certificate.getInstance(it)) }
            // Sorted by subject so the order the bag happens to be in does not
            // show up as a difference.
            .sortedBy { it.subject.toString() }
            .forEachIndexed { i, holder -> describeCertificate(out, "$prefix.cert[$i]", holder, verbose) }

        val crls = signedData.crLs?.toArray()?.toList().orEmpty()
        out.line("$prefix.crls", crls.size)

        val signers = signedData.signerInfos.toArray().toList()
        out.line("$prefix.signerInfos", signers.size)
        signers.forEachIndexed { i, it ->
            describeSignerInfo(out, "$prefix.signer[$i]", SignerInfo.getInstance(it), verbose)
        }
    }

    private fun describeCertificate(
        out: Report,
        prefix: String,
        holder: X509CertificateHolder,
        verbose: Boolean,
    ) {
        out.line("$prefix.subject", holder.subject.toString())
        out.line("$prefix.issuer", holder.issuer.toString())
        out.line("$prefix.selfIssued", holder.subject == holder.issuer)
        out.line("$prefix.sigAlg", algorithm(holder.signatureAlgorithm))
        out.line("$prefix.keyAlg", oid(holder.subjectPublicKeyInfo.algorithm.algorithm))
        out.line("$prefix.keyBits", publicKeyBits(holder))
        val keyUsage = holder.getExtension(Extension.keyUsage)
        out.line("$prefix.keyUsage", keyUsage?.let { keyUsageOf(it) } ?: ABSENT)
        out.line(
            "$prefix.basicConstraintsCA",
            holder.getExtension(Extension.basicConstraints)?.let {
                org.bouncycastle.asn1.x509.BasicConstraints.getInstance(it.parsedValue).isCA
            } ?: ABSENT,
        )
        out.line(
            "$prefix.certificatePolicies",
            holder.getExtension(Extension.certificatePolicies)?.let { extension ->
                ASN1Sequence.getInstance(extension.parsedValue).toArray()
                    .map { oid(ASN1Sequence.getInstance(it).getObjectAt(0) as ASN1ObjectIdentifier) }
                    .sorted()
                    .joinToString(",")
            } ?: ABSENT,
        )
        out.line("$prefix.extensions", extensionOids(holder))
        if (verbose) {
            out.line("$prefix.serial", holder.serialNumber.toString(16))
            out.line("$prefix.notBefore", holder.notBefore.toInstant().toString())
            out.line("$prefix.notAfter", holder.notAfter.toInstant().toString())
        }
    }

    private fun describeSignerInfo(
        out: Report,
        prefix: String,
        signer: SignerInfo,
        verbose: Boolean,
    ) {
        out.line("$prefix.version", signer.version.value)
        val sid = signer.sid.id
        if (sid is ASN1OctetString) {
            out.line("$prefix.sid.form", "subjectKeyIdentifier")
        } else {
            out.line("$prefix.sid.form", "issuerAndSerialNumber")
            runCatching { IssuerAndSerialNumber.getInstance(sid) }.getOrNull()?.let {
                out.line("$prefix.sid.issuer", it.name.toString())
                if (verbose) out.line("$prefix.sid.serial", it.serialNumber.value.toString(16))
            }
        }
        out.line("$prefix.digestAlgorithm", algorithm(signer.digestAlgorithm))
        // The one most likely to be spelled differently by two implementations
        // and still be legal: RFC 8933 / RFC 5754 allow rsaEncryption here, and
        // plenty of signers write sha256WithRSAEncryption instead.
        out.line("$prefix.signatureAlgorithm", algorithm(signer.digestEncryptionAlgorithm))
        out.line("$prefix.signatureBytes", signer.encryptedDigest.octets.size)

        describeAttributes(out, "$prefix.signedAttrs", signer.authenticatedAttributes, verbose)
        describeAttributes(out, "$prefix.unsignedAttrs", signer.unauthenticatedAttributes, verbose)
    }

    private fun describeAttributes(out: Report, prefix: String, set: ASN1Set?, verbose: Boolean) {
        if (set == null) {
            out.line("$prefix.count", ABSENT)
            return
        }
        val attributes = set.toArray().map { Attribute.getInstance(it) }
        out.line("$prefix.count", attributes.size)
        out.line(
            "$prefix.oids",
            attributes.map { attributeName(it.attrType) }.sorted().joinToString(","),
        )
        for (attribute in attributes.sortedBy { it.attrType.id }) {
            val name = attributeName(attribute.attrType)
            val values = attribute.attrValues.toArray().toList()
            out.line("$prefix.$name.values", values.size)
            val value = values.firstOrNull() ?: continue
            out.line("$prefix.$name.shape", shapeOf(value.toASN1Primitive(), verbose))
        }
    }

    // --- rendering helpers ---------------------------------------------------

    private const val ABSENT = "(absent)"
    private const val PRESENT = "(present)"

    private fun present(value: String?): String =
        if (value.isNullOrEmpty()) ABSENT else "(present, ${value.length} chars)"

    private fun shapeOf(value: ASN1Primitive, verbose: Boolean): String = when {
        value is ASN1ObjectIdentifier -> oid(value)
        value is ASN1OctetString -> "OCTET STRING ${value.octets.size} bytes" +
            if (verbose) " ${value.octets.toHex()}" else ""
        value is ASN1UTCTime -> "UTCTime" + if (verbose) " ${value.time}" else ""
        value is ASN1Integer -> "INTEGER" + if (verbose) " ${value.value}" else ""
        value is ASN1String -> "${value::class.java.simpleName}" +
            if (verbose) " ${value.string}" else ""
        value is ASN1Sequence -> "SEQUENCE (${value.size()} items)"
        value is ASN1Set -> "SET (${value.size()} items)"
        value === DERNull.INSTANCE -> "NULL"
        else -> value::class.java.simpleName
    }

    private fun algorithm(id: AlgorithmIdentifier): String {
        val parameters = when {
            id.parameters == null -> "no params"
            id.parameters == DERNull.INSTANCE -> "params=NULL"
            else -> "params=${id.parameters.toASN1Primitive()::class.java.simpleName}"
        }
        return "${oid(id.algorithm)} ($parameters)"
    }

    private fun oid(id: ASN1ObjectIdentifier): String =
        OID_NAMES[id.id]?.let { "$it[${id.id}]" } ?: id.id

    private fun attributeName(id: ASN1ObjectIdentifier): String = OID_NAMES[id.id] ?: id.id

    private fun publicKeyBits(holder: X509CertificateHolder): String = runCatching {
        val key = holder.subjectPublicKeyInfo.parsePublicKey()
        val modulus = ASN1Sequence.getInstance(key).getObjectAt(0) as ASN1Integer
        modulus.positiveValue.bitLength().toString()
    }.getOrDefault("?")

    private fun keyUsageOf(extension: Extension): String {
        val usage = KeyUsage.getInstance(extension.parsedValue)
        val names = listOf(
            KeyUsage.digitalSignature to "digitalSignature",
            // The bit a 署名用 certificate is expected to assert.
            KeyUsage.nonRepudiation to "nonRepudiation",
            KeyUsage.keyEncipherment to "keyEncipherment",
            KeyUsage.dataEncipherment to "dataEncipherment",
            KeyUsage.keyAgreement to "keyAgreement",
            KeyUsage.keyCertSign to "keyCertSign",
            KeyUsage.cRLSign to "cRLSign",
        )
        val set = names.filter { (bit, _) -> usage.hasUsages(bit) }.map { it.second }
        return if (set.isEmpty()) "none" else set.joinToString("+")
    }

    private fun extensionOids(holder: X509CertificateHolder): String =
        holder.extensions?.extensionOIDs
            ?.map { attributeName(it) }
            ?.sorted()
            ?.joinToString(",")
            ?: ABSENT

    /** Whether the object re-encodes to exactly the bytes it was read from. */
    private fun ASN1Primitive.isCanonicalDer(contents: ByteArray): Boolean = runCatching {
        val der = getEncoded(ASN1Encoding.DER)
        der.size <= contents.size && der.indices.all { der[it] == contents[it] }
    }.getOrDefault(false)

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(Locale.ROOT, it) }

    private fun List<Byte>.toHex(): String =
        joinToString("") { "%02x".format(Locale.ROOT, it) }

    private class Report {
        private val builder = StringBuilder()
        fun line(key: String, value: Any?) {
            builder.append(key).append(" = ").append(value).append('\n')
        }
        override fun toString(): String = builder.toString()
    }

    /**
     * Just the OIDs worth reading as names in a report. Anything absent prints as
     * its dotted form, which is what a search engine wants anyway.
     */
    private val OID_NAMES = mapOf(
        // Content and attribute types
        "1.2.840.113549.1.7.1" to "id-data",
        "1.2.840.113549.1.7.2" to "id-signedData",
        "1.2.840.113549.1.9.3" to "contentType",
        "1.2.840.113549.1.9.4" to "messageDigest",
        "1.2.840.113549.1.9.5" to "signingTime",
        "1.2.840.113549.1.9.15" to "smimeCapabilities",
        "1.2.840.113549.1.9.16.2.12" to "signingCertificate",
        "1.2.840.113549.1.9.16.2.47" to "signingCertificateV2",
        "1.2.840.113549.1.9.16.2.14" to "signatureTimeStampToken",
        "1.2.840.113549.1.9.16.2.15" to "sigPolicyId",
        "1.2.840.113549.1.9.16.2.7" to "contentHints",
        "1.2.840.113583.1.1.8" to "adbe-revocationInfoArchival",
        // Algorithms
        "1.2.840.113549.1.1.1" to "rsaEncryption",
        "1.2.840.113549.1.1.5" to "sha1WithRSAEncryption",
        "1.2.840.113549.1.1.10" to "rsassaPss",
        "1.2.840.113549.1.1.11" to "sha256WithRSAEncryption",
        "1.2.840.113549.1.1.12" to "sha384WithRSAEncryption",
        "1.2.840.113549.1.1.13" to "sha512WithRSAEncryption",
        "1.3.14.3.2.26" to "sha1",
        "2.16.840.1.101.3.4.2.1" to "sha256",
        "2.16.840.1.101.3.4.2.2" to "sha384",
        "2.16.840.1.101.3.4.2.3" to "sha512",
        // Certificate extensions
        "2.5.29.14" to "subjectKeyIdentifier",
        "2.5.29.15" to "keyUsage",
        "2.5.29.17" to "subjectAltName",
        "2.5.29.19" to "basicConstraints",
        "2.5.29.31" to "cRLDistributionPoints",
        "2.5.29.32" to "certificatePolicies",
        "2.5.29.35" to "authorityKeyIdentifier",
        "2.5.29.37" to "extendedKeyUsage",
        "1.3.6.1.5.5.7.1.1" to "authorityInfoAccess",
        // JPKI private arc, so a real card's certificate is legible here
        "1.2.392.200149.8.5.5.1" to "jpki-name",
        "1.2.392.200149.8.5.5.2" to "jpki-birthDate",
        "1.2.392.200149.8.5.5.3" to "jpki-sex",
        "1.2.392.200149.8.5.5.4" to "jpki-address",
    )
}

/**
 * Differing lines between two reports, as a side-by-side list.
 *
 * A plain textual diff over reports this shape is mostly noise -- the keys are
 * stable, so what matters is which keys disagree, not where they moved to.
 */
object ReportDiff {

    fun of(leftName: String, left: String, rightName: String, right: String): String {
        val leftMap = parse(left)
        val rightMap = parse(right)
        val keys = (leftMap.keys + rightMap.keys).sorted()
        val builder = StringBuilder()
        var differences = 0
        for (key in keys) {
            val a = leftMap[key]
            val b = rightMap[key]
            if (a == b) continue
            differences++
            builder.append(key).append('\n')
            builder.append("  ").append(leftName).append(": ").append(a ?: "(no such key)").append('\n')
            builder.append("  ").append(rightName).append(": ").append(b ?: "(no such key)").append('\n')
        }
        return if (differences == 0) {
            "no differences across ${keys.size} keys\n"
        } else {
            "$differences of ${keys.size} keys differ\n\n$builder"
        }
    }

    private fun parse(report: String): Map<String, String> = report
        .lineSequence()
        .filter { it.contains(" = ") }
        .associate { it.substringBefore(" = ") to it.substringAfter(" = ") }
}
