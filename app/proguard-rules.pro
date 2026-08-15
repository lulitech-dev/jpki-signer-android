# BouncyCastle is used as an ASN.1/CMS structure library, never as a JCE
# provider (PLAN.md §5.1), so the usual provider-reflection keep rules are not
# needed. R8 is free to shrink the unused cipher/EC/TLS bulk.

# PDFBox-Android resolves some COS/font classes reflectively.
-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**

# BouncyCastle references optional JCE/JSSE surface that is absent on Android.
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
