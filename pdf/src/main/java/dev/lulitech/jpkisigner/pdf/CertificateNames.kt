package dev.lulitech.jpkisigner.pdf

import org.bouncycastle.asn1.ASN1ObjectIdentifier
import org.bouncycastle.asn1.ASN1Sequence
import org.bouncycastle.asn1.ASN1String
import org.bouncycastle.asn1.ASN1TaggedObject
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x500.style.IETFUtils
import org.bouncycastle.cert.X509CertificateHolder

internal object CertificateNames {

    /**
     * JPKI private extension holding the holder's name (氏名), one of the
     * 基本4情報 fields carried by the 署名用証明書.
     *
     * The certificate's `CN` is an opaque identifier, not a name, so a signature
     * whose /Name came from `CN` would show a meaningless string. The reference
     * implementation reads the name through the middleware's
     * `JPKIUserCertBasicData.getName()`; over NFC we read the same value out of
     * this extension.
     *
     * The sibling OIDs in this arc carry sex, date of birth and address. They are
     * deliberately not read: nothing in a PDF signature needs them, and not
     * reading them is the simplest way not to leak them.
     */
    private const val JPKI_NAME_OID = "1.2.392.200149.8.5.5.1"

    /**
     * The name to put in the signature dictionary: the JPKI name extension when
     * present, otherwise the subject `CN`.
     *
     * The fallback matters for the 認証用 certificate and for any non-JPKI
     * certificate used in tests, neither of which carries the extension.
     */
    fun holderName(derCertificate: ByteArray): String? {
        val holder = X509CertificateHolder(derCertificate)
        return jpkiName(holder) ?: commonName(holder)
    }

    /**
     * Reads the name from `subjectAltName`.
     *
     * These fields are **not** standalone certificate extensions -- looking the
     * OID up with `getExtension` returns null. They are `otherName` entries
     * inside `subjectAltName`:
     *
     * ```
     * GeneralName ::= otherName [0] OtherName
     * OtherName   ::= SEQUENCE { type-id OBJECT IDENTIFIER, value [0] EXPLICIT ANY }
     * ```
     *
     * On a real 署名用証明書 the value is a UTF8String.
     */
    private fun jpkiName(holder: X509CertificateHolder): String? = runCatching {
        val san = holder.getExtension(Extension.subjectAlternativeName) ?: return null
        GeneralNames.getInstance(san.parsedValue).names
            .asSequence()
            .filter { it.tagNo == GeneralName.otherName }
            .mapNotNull { generalName ->
                val other = ASN1Sequence.getInstance(generalName.name)
                val typeId = ASN1ObjectIdentifier.getInstance(other.getObjectAt(0))
                if (typeId.id != JPKI_NAME_OID) return@mapNotNull null
                val wrapped = ASN1TaggedObject.getInstance(other.getObjectAt(1))
                (wrapped.baseObject.toASN1Primitive() as? ASN1String)?.string
            }
            .firstOrNull { it.isNotBlank() }
    }.getOrNull()

    private fun commonName(holder: X509CertificateHolder): String? {
        val rdns = holder.subject.getRDNs(BCStyle.CN)
        if (rdns.isEmpty()) return null
        return IETFUtils.valueToString(rdns[0].first.value)
    }
}
