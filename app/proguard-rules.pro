# BouncyCastle is used as an ASN.1/CMS structure library, never as a JCE
# provider (DESIGN.md §5.1), so the usual provider-reflection keep rules are not
# needed. R8 is free to shrink the unused cipher/EC/TLS bulk.
#
# Checked rather than assumed: `minifyReleaseWithR8` completes with no warnings,
# and no source file in :app, :pdf or :jpki calls Class.forName, newInstance,
# getMethod, Security.addProvider or setProvider -- the whole CMS path is direct
# calls, which is what makes shrinking it safe. R8 does drop classes like
# SignerInfoGeneratorBuilder from the mapping, but by inlining a builder into its
# only caller, not by breaking a reference it could not see.
#
# Still worth a smoke test on a device before a release: nothing here proves a
# signature produced by the shrunk build verifies. Sign one PDF from a release
# APK when the signing config exists.

# PDFBox-Android resolves some COS/font classes reflectively.
-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**

# BouncyCastle references optional JCE/JSSE surface that is absent on Android.
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
