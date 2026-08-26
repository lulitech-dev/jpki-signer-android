# Design notes

The decisions the source comments cite, in the numbering they cite. `PLAN.md` is
untracked working notes; this file is the tracked subset that the code refers to,
so a fresh clone can follow every `DESIGN.md §x` reference in a comment.

---

## §2. Decisions locked in

| Area | Decision |
|---|---|
| Language / UI | Kotlin + Jetpack Compose, Material 3 |
| minSdk / targetSdk | 26 (Android 8.0) / 36 (Android 16) |
| v1 scope | Invisible signature only; a visible stamp is deferred |
| Localisation | Japanese default (`values/`), English (`values-en/`), plus an in-app picker |
| DocMDP | **Never written.** Approval signatures only — see §3.5 |
| Import / export | Share-in (`ACTION_SEND` + `ACTION_VIEW`); export via the share sheet. No SAF pickers. |
| Preview | None — filename only. The user reviews the PDF in the source app before sharing it in. |
| Timestamp (TSA) | None. The app stays fully offline. |
| **Network** | **No `INTERNET` permission at all.** Nothing in the app connects to anything. Opening the homepage link in About hands off to the browser via `ACTION_VIEW`, which needs no permission. |
| App id | `dev.lulitech.jpkisigner` |
| Licence | BSD 2-Clause, © LULITECH G.K. / ルリテック合同会社 |
| Notices | Full licence texts ship in the app, reachable from About |

---

## §3. Architecture

```
app/    Compose UI, NFC reader-mode host, PIN entry
jpki/   card layer — IsoDep + APDUs.  No PDFBox, no BouncyCastle.
pdf/    PDFBox-Android + CMS assembly.  No NFC.
```

The two core modules share **only byte arrays**, across
`pdf/…/SignatureProvider.kt`:

```
:pdf  ──  DigestInfo (51 bytes)   ──▶  :jpki
:pdf  ◀──  RSA signature (256 B)  ───  :jpki
```

Consequences worth preserving:

- `:pdf` is unit-testable against a **software RSA key** — no card, no phone.
  That is what `SoftwareSignatureProvider` in `pdf`'s test source set is for.
- `:jpki` is testable without ever constructing a PDF.
- Short APDUs only, so the card is allowed to answer `61 xx` ("more waiting, send
  GET RESPONSE") or `6C xx` ("wrong Le, use this one"). Neither is a failure and
  both are followed, bounded, in `JpkiSession.sendForData` — but never anywhere
  near `VERIFY`, which reissues nothing.
- Neither module can reach for the other's dependencies, so the card layer cannot
  acquire a PDF parser and the PDF layer cannot acquire NFC.

### §3.1 Signing flow (single card tap)

1. The user opens a document from the library, sets reason/location, enters a PIN.
2. **Rehearsal.** Everything about the document that can fail is settled before
   the first APDU: it still validates, it is still not certified against change,
   and an output file can be created beside it. See §6.
3. `:pdf` calls `saveIncrementalForExternalSigning()`, which yields the byte range
   to be covered.
4. **One card session:** `SELECT AP` → read retry counter (free) → `VERIFY PIN`
   (exactly once) → read signer + CA certificate → build `signedAttrs` → DER →
   SHA-256 → `COMPUTE DIGITAL SIGNATURE`.
5. `:pdf` assembles the detached CMS and calls `setSignature()`.
6. The signed file replaces the head file by a single `rename`.

This replaces the reference implementation's blocking `SignatureInterface`
callback, which would mean blocking a save thread on a user's card tap. The card
interaction becomes one explicit step instead.

The certificate is read *after* `VERIFY` because the 署名用証明書 is
PIN-protected — it carries name, address and date of birth.

### §3.2 Storage and backup

Documents live in `filesDir/documents/<id>/<the file's real name>`. The id is in
the directory name so it never reaches a recipient; the filename is the display
name, because `FileProvider` reports a file's on-disk name when sharing out.

The signed file is assembled as `signing.part` in the same directory, under a
**fixed** name rather than one derived from the document's. A stored name is
allowed to reach NAME_MAX (255 bytes) exactly, so a suffix on top of one cannot be
created at all — and being discovered only during the write, it used to cost a
PIN attempt on a document that could never have been signed. It is not a `.pdf`,
so the library cannot see it, and the rehearsal in §6 sweeps any copy a killed run
left behind.

Signed documents are sensitive, so `res/xml/data_extraction_rules.xml` excludes
everything from both cloud backup and device-to-device transfer, and
`allowBackup` is false.

### §3.3 Signature history and the revision stack

**History is read from the PDF, not from a local log.** Screen 2 parses the
AcroForm signature dictionaries of the head file, so co-signers' signatures and
signatures already present on an imported file display identically to ones this
app made, and there is no local database to drift out of sync.

A PDF signature covers `[0, a) + [b, b + c)` of its ByteRange, and `b + c` is the
end of the revision that signature created. An incremental update never rewrites
the original bytes — `PdfSignerTest` asserts this directly rather than trusting
the format — so a signature's own revision end is exact and needs no local
bookkeeping. Removing a signature is a truncation to a revision boundary, which
yields the byte-identical earlier revision.

**The previous signature's revision end is only a lower bound.** It was tempting
to say the length before signature *i* is simply the length after signature
*i − 1*, and that is wrong wherever a document gained an *unsigned* incremental
update in between — a form filled in, a page a viewer annotated. Signature *i*
signed that update, so the revision it was applied to ends later than *i − 1*
does, and truncating to *i − 1* deletes the user's own work along with the
signature. Nothing catches it downstream: the result is a readable PDF carrying
exactly the number of signatures expected of it.

Truncation can only ever *remove* signatures; it cannot fabricate one. It can,
however, remove unsigned content if the wrong boundary is chosen, which is why
`PdfRevisions.boundaryBefore` searches `%%EOF` candidates from the newest
downwards — at every index, not only the first, and bounded below by the previous
signature's revision end, which is also the answer when there is no revision in
between. See the comment there.

**A `%%EOF` is not a boundary on its own.** The marker appears in uncompressed
streams, in metadata strings, and complete with its own trailer inside an
embedded PDF attachment. Parsing the truncated result does not settle it either:
PDFBox reconstructs the cross-reference table of a file it cannot read normally,
so a prefix cut mid-body can still come back as a readable, zero-signature
document with pages — and being the larger offset, it would be tried before the
real boundary. So a candidate must also carry a trailer whose `startxref` names
an offset that really addresses a cross-reference section *in this file*, which
is what rejects the embedded-attachment case: its offsets are relative to the
inner file. A candidate that fails this is not offered at all, and the signature
is reported as not removable — the direction to fail in.

**An unreadable document is not an unsigned one.** Reading the history costs a
full in-memory parse of a file whose size is not ours to bound, so
`OutOfMemoryError` is an expected outcome rather than a broken VM. Every failure
used to fall back to an empty list — which renders exactly like a genuinely
unsigned file, so a document too large to parse looked unsigned and silently
offered no way to remove anything from it. `DocumentDetailUi.unreadable` keeps the
two apart, because "this document has no signatures" is a claim, and it can only
be made about a document we actually read.

**What the list is allowed to claim.** A signature list reads as a validity
statement, so it shows only what an offline app can actually prove:

| Claim | Shown? |
|---|---|
| Signer name, signing time, reason / location | ✅ from the embedded certificate and signature dict |
| Signature matches the bytes it covers | ✅ CMS verification, no trust store needed |
| Document unmodified after this signature | ✅ but document-level, not per-signature |
| Certificate chains to the JPKI root | ❌ would require embedding the root CA |
| **Certificate not revoked** | ❌ needs OCSP/CRL, i.e. network — which we do not have |

**"Did not match" and "could not check" are different sentences.**
`SignatureIntegrity` has three states, not two. The verifier here is RSA-only —
the whole `SignatureProvider` contract is, since the card signs one way — so an
imported or co-signed PDF carrying an ECDSA signature cannot be checked at all.
While that shared a boolean with a failed check, an intact document was told its
file might be damaged. Only one throw is evidence about the document: BouncyCastle
raises `CMSSignerDigestMismatchException` when the message-digest attribute
disagrees with the covered bytes. Everything else — an unimplemented algorithm, a
CMS that will not parse, a signer certificate missing from the bundle — is a limit
of this app, is shown as such, and is not coloured as an error.

A revoked signing certificate passes every offline check available here. **So the
app states nothing positive about a signature at all.** A success message would be
worth little — a user cannot act on "this checked out" — and any positive
statement invites being read as the app confirming the signature, which it is in
no position to do. Confirming a signature is the 法務省 plugin's job. The UI
speaks only when something is wrong.

### §3.4 Swipe-to-delete and the cascade

**Oldest at the top, newest at the bottom.** A signature history is append-only
and chronological, so it reads forward in time like a message thread, and
"everything after this" maps onto "below" — matching the animation and the
wording.

**The cascade gesture.** Swiping signature *n* drags every later signature off the
screen with it, simultaneously, because those signatures really are one
indivisible unit: signature *n+1* signed the bytes containing signature *n*. This
requires the drag offset to be hoisted **above** the rows — a per-row
`SwipeToDismissBox` owns its own offset and structurally cannot move its
neighbours — so the list uses a shared offset plus an anchor index. The gesture is
the explanation.

Deletion is also reachable by long press and by a named accessibility action: a
destructive action reachable only by dragging excludes TalkBack users and anyone
with limited motor control. All three paths lead to the same confirmation.

### §3.5 DocMDP is read, never written

A DocMDP entry marks a *certification* signature. This app never writes one; its
signatures are ordinary approval signatures, which is the weaker and more honest
claim — we are adding a signature, not certifying anyone's document. The reference
implementation does not write one either: its `setMDPPermission` is public but
called from nowhere.

What it does do is *read* the permission and refuse to sign a document certified
against all changes. That refusal happens at import (`PdfValidator`), and again in
the rehearsal before the first APDU, so it cannot land after a PIN attempt has
been spent. `PdfSigner` checks a third time as a last line of defence, before it
asks the card for anything.

---

## §5. PDF layer

### §5.1 BouncyCastle: library, never a JCE provider

Android registers a provider *named* `"BC"` at boot, so `Security.addProvider(...)`
is a silent no-op and any `.setProvider("BC")` call routes to AOSP's stripped
fork — whose contents have been shrinking since API 28. We sidestep this
completely by never registering a provider:

| Needs a provider | Ours (pure BC, no JCA) |
|---|---|
| `JcaDigestCalculatorProviderBuilder` | `BcDigestCalculatorProvider` |
| `JcaSignerInfoGeneratorBuilder` | `SignerInfoGeneratorBuilder(BcDigestCalculatorProvider())` |
| `JcaCertStore` + `CertificateFactory` | `CollectionStore(listOf(X509CertificateHolder(der)))` |
| `DefaultSignatureAlgorithmIdentifierFinder` → `Signature` | our own `ContentSigner` |

The last row makes this easy: the `ContentSigner` was always going to be ours,
because the private key is on the card. There is no `Signature` object to obtain,
so the provider question never arises. `sha256WithRSAEncryption` is a hardcoded
OID.

**Android's bundled BouncyCastle is not an option.** The boot-classpath module is
`bcprov` only; bcpkix — where `cms.*`, `cert.*` and `operator.*` live — never
reaches it. It is also a non-SDK interface, so it would be reflection-only and
subject to hidden-API blocking. Java has no standard CMS API to fall back on.

Because nothing here is reflective, R8 is free to shrink the unused cipher, EC
and TLS bulk; see `app/proguard-rules.pro`.

### §5.1a CMS encoding gotchas

Two defects that both produce a signature which *encodes* fine and verifies
nowhere — the silent-failure class that argued for using BouncyCastle rather than
hand-rolled DER:

- **Emit DER, not BER.** `CMSSignedDataGenerator.generate()` defaults to BER when
  the content is streamed, producing an indefinite-length `SEQUENCE` (`30 80`)
  terminated by `00 00` end-of-contents octets. PDFBox then zero-pads `/Contents`
  to the reserved size, and a BER parser reads that padding as a sea of EOC
  markers. Use `getEncoded(ASN1Encoding.DER)` — which is what the PDF spec
  requires for `adbe.pkcs7.detached` regardless.
- **`/Contents` is always padded, so parse it as a stream.**
  `CMSSignedData(byte[])` routes through `ASN1Primitive.fromByteArray`, which is
  strict and throws `IOException: Extra data detected in stream` on the trailing
  zeros. Read one object via `ASN1InputStream` and ignore the remainder.

Also: do **not** close the `InputStream` from
`ExternalSigningSupport.getContent()`. It is backed by PDFBox's writer, and
closing it corrupts the output being assembled.

### §5.2 Dependency hygiene

pdfbox-android pulls **`bcprov-jdk15to18`** transitively. The `jdk15on` /
`jdk15to18` / `jdk18on` artifacts all publish the *same* `org.bouncycastle.*`
packages — they are JDK-target variants, not namespaces — so mixing families
causes duplicate-class errors or silent version skew.

Rules, as implemented in `pdf/build.gradle.kts` and `gradle/libs.versions.toml`:

- Align everything on the **`jdk15to18`** family via `replacedBy` module rules.
- **Pin each artifact to its own latest patch — do NOT force one shared version
  string.** The artifacts patch independently, and `bcutil 1.85` ships a duplicate
  copy of bcprov's `org.bouncycastle.asn1.iana.IANAObjectIdentifiers`, which fails
  `:app:checkDebugDuplicateClasses`. `bcutil 1.85.1` is the fix release — and
  `bcpkix 1.85`'s own POM declares the broken `bcutil 1.85`.
- **Scope the pinning to `*CompileClasspath` / `*RuntimeClasspath` only.** Applying
  it to every configuration also hits AGP's internal `androidLintTool`, which
  legitimately resolves the `jdk18on` family — where our `jdk15to18` patch
  versions do not exist.
- Check with `./gradlew :pdf:dependencies` that exactly one BC family resolves.

---

## §6. PIN safety rules

The 署名用 PIN blocks after 5 failed attempts and can only be reset in person at a
municipal window, so every rule here exists to avoid spending an attempt the user
did not intend:

- **Everything checkable offline is checked offline.** The card cannot tell a
  mistyped PIN from a wrongly encoded one; both cost an attempt. Length and
  character set are validated before any APDU is sent, and the PIN field
  uppercases as the user types.
- **The document is rehearsed before the card is touched.**
  `DocumentSigner.rehearse` re-runs `PdfValidator` and proves an output file can
  be created, before the first command goes out. The write used to happen only
  after the `VERIFY`, so a document that would not parse, that had been certified
  since it was imported, or whose name left no room for the staging file cost an
  attempt to discover — on the one key a lockout cannot be recovered from at
  home. It costs a second parse of the document; that is work already being done
  with the card in the field, and a card lifted during it spends nothing.
- **Cancelling and a card tap are mutually exclusive.** Both take the armed run
  out of the same atomic slot, so exactly one of them wins: a cancel that arrives
  first means the tap finds nothing to run, and a tap that arrives first means the
  sheet refuses to close. It used to be possible to cancel over a run already
  taken, which closed the sheet on a signature that still landed — after an
  attempt had been spent, with nothing on screen to show for it.
- **Read the retry counter first.** A `VERIFY` with an empty data field returns
  `63 Cx` *without* decrementing, which makes it safe to call before every real
  verify. The run is abandoned rather than spending the last attempt on a typo.
- **Exactly one `VERIFY`, never retried.** If the card leaves the field
  mid-`VERIFY` there is no way to know whether it counted the attempt, so a retry
  can spend two attempts on one user action. That outcome has its own name,
  `CardProblem.VerifyOutcomeUnknown`, and is never presented as either a wrong PIN
  or a no-op.
- **Refusing near the floor must not be a one-way door.** The run stops below two
  remaining attempts, because a typo there is a trip to a municipal window. But
  only a *successful* `VERIFY` resets the counter, so an app that refuses at the
  floor and offers nothing else can never send one again: the card would stay
  unusable here for good, and the user would have to go and find other software.
  `SignFailure.TooFewAttempts.canOverride` offers the way past, and it leads back
  to the form rather than straight to a card — retyping the PIN is the
  confirmation, on the one attempt whose loss cannot be undone at home. It lowers
  our floor, never the card's: a counter already at zero is refused either way.
- **Never state a remaining count we did not get from the card.**
  `SignFailure.remainingAttempts` is null on every path that did not establish it.
- **The PIN is a `CharArray` and is wiped** — including the encoded copy and the
  command APDU that carries it, and including a run that is armed and then
  cancelled.
- **The run outlives the activity.** It belongs to the ViewModel, so a
  configuration change cannot strand a signature in flight and leave the user with
  no result after an attempt was spent. For the same reason nothing in the run may
  let a `Throwable` escape onto the NFC binder thread — and that includes the
  reader itself, not only the run it dispatches: `IsoDep.connect` throws
  `IOException` for the most ordinary thing a user does, holding the card slightly
  off the antenna, and a checked exception walks straight out of a Kotlin lambda
  past every guard the run puts around itself.
- **A tap that produces no session is still an answer.** It has already taken the
  armed run out of the slot, so saying nothing left the sheet asking for a card
  that had come and gone. `NfcCardReader` reports it instead —
  `CardProblem.NotJpkiCard` for the wrong card, `LostContact` for one that could
  not be reached. No command was sent on either path, so nothing was spent.
