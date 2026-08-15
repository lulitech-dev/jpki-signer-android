package dev.lulitech.jpkisigner.pdf

import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x500.style.IETFUtils
import org.bouncycastle.cert.X509CertificateHolder

internal object CertificateNames {

    /**
     * The signer's common name, read straight from the certificate subject.
     *
     * The reference implementation reads this from the card's
     * `JPKIUserCertBasicData`; taking it from the certificate avoids an extra
     * card read for a value we already hold.
     */
    fun commonNameOf(derCertificate: ByteArray): String? {
        val subject = X509CertificateHolder(derCertificate).subject
        val rdns = subject.getRDNs(BCStyle.CN)
        if (rdns.isEmpty()) return null
        return IETFUtils.valueToString(rdns[0].first.value)
    }
}
