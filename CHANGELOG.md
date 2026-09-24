# Changelog

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project follows [Semantic Versioning](https://semver.org/).

## [1.0.0] — 2026-09-25

First release. Requires Android 8.0 (API 26) and a device with NFC.

### Signing

- Signs PDFs with the 署名用電子証明書 on a マイナンバーカード, read over NFC,
  entirely on the device. One card tap per signature.
- Produces a detached PKCS#7 (`adbe.pkcs7.detached`), SHA-256 with RSA,
  carrying both the signer certificate and its issuing CA.
- Output is byte-identical to jpki-pdf-signer's apart from the trailer `/ID`,
  the signing time and `/Prop_Build`. Signatures produced this way are accepted
  by 法務省's 登記・供託オンライン申請システム.
- Invisible signatures only; a visible stamp is not in this release.
- Approval signatures only. The app never writes a DocMDP certification, and
  refuses to sign a document that is certified against further change.

### Documents

- PDFs arrive by share-in (`ACTION_SEND`) or "Open with" (`ACTION_VIEW`), and
  leave through the Android share sheet under a filename you choose. There are
  no file pickers.
- Signature history is read from the PDF itself, so co-signed and imported
  documents behave exactly like ones signed here.
- Removing a signature truncates the file to a revision boundary, yielding the
  byte-identical earlier revision. Later signatures go with it, because they
  signed the bytes containing it.
- A document that cannot be read, and one too large for the device to parse,
  are reported as different things — neither is called unsigned.

### PIN safety

The 署名用 PIN blocks after five failed attempts and can only be reset in
person at a municipal window, so:

- Length and character set are checked before any command is sent.
- The remaining-attempt counter is read first, which costs nothing, and the
  run stops rather than spending the last attempt on a typo. Proceeding anyway
  is offered, and requires retyping the PIN.
- Exactly one VERIFY is sent, and never retried. If contact is lost mid-VERIFY
  the outcome is reported as unknown rather than guessed at.
- The document is fully checked before the card is touched, so nothing that
  could never have been signed costs an attempt.

### Privacy

- No `INTERNET` permission. Nothing is uploaded, and there is no timestamp
  authority. NFC is the only permission requested.
- Signed documents are excluded from cloud backup and device-to-device
  transfer.
- The app states nothing positive about a signature it cannot prove offline.
  Confirming a signature is the filing system's job.

### Interface

- Japanese and English, with an in-app language picker.
- Signatures can be removed by swipe, by long press, or by a named
  accessibility action.
