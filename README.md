# JPKI Signer for Android

Signs PDFs with the 署名用電子証明書 on a Japanese My Number Card
(マイナンバーカード), over NFC, entirely on the device.

That signature is the electronic counterpart of a 実印 — a registered personal
seal — together with its 印鑑証明書, so it stands in for one wherever a document
calls for it. Registration (登記) filings with **Japan's Ministry of Justice**
(法務省) are the case it has been tested against, not the limit of its use.

- **Offline by construction.** No `INTERNET` permission. Nothing is uploaded,
  and there is no timestamp authority.
- **Approval signatures only.** The app never writes a DocMDP certification, and
  refuses to sign a document that is certified against further change.
- **Signature history read from the PDF**, not from a local log, so imported and
  co-signed documents behave identically to ones signed here. Removing a
  signature truncates to a revision boundary and yields the byte-identical
  earlier revision.
- **It claims nothing it cannot prove.** Revocation needs network, so the app
  says nothing positive about a signature at all and speaks only when something
  is wrong. Confirming a signature is the job of the PDF署名プラグイン from
  **Japan's Ministry of Justice** (法務省).

PDFs arrive by share-in (`ACTION_SEND`) or "Open with" (`ACTION_VIEW`), and
leave through the Android share sheet. There are no file pickers.

## Modules

| Module | Contents |
|---|---|
| `app` | Compose UI, NFC reader-mode host, PIN entry, document store |
| `jpki` | Card layer: `IsoDep` and APDUs. Ships no PDFBox or BouncyCastle. |
| `pdf` | PDFBox-Android and CMS assembly. No NFC. |

`jpki` and `pdf` exchange only byte arrays, which is what lets each be tested
without the other — and lets the whole PDF/CMS path be verified with a software
RSA key, no card and no phone.

`jpki` does take BouncyCastle as a `testImplementation`, to check its hardcoded
DigestInfo prefix against a real ASN.1 encoder. Nothing in the shipped card
layer imports it.

## Building

Built with JDK 21 and the Android SDK (`compileSdk` 36).

```sh
./gradlew test          # all unit tests, no device needed
./gradlew assembleDebug
```

`CardDebugActivity` is a card bring-up screen in `app/src/debug` only: it
verifies PINs and logs certificate subjects, so it is not present in a release
build.

Design decisions, and the rationale the source comments cite, are in
[DESIGN.md](DESIGN.md).

## Licence

BSD 2-Clause. See [LICENSE](LICENSE). Third-party notices ship in the app and
are reachable from the About dialog.
