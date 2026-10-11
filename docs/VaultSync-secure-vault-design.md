# VaultSync secure-vault design

## Product boundary

VaultSync is based on PlainApp. Keep existing PlainApp file management and browser playback working. The secure vault is a separate feature: files enter it only when the user explicitly imports or moves a copy into the vault. Existing files remain under existing PlainApp behavior unless the user separately chooses to remove them.

No dedicated USB transport is planned. Reuse the existing browser dashboard and HTTP server.

## Security requirements

- Encrypt vault payloads at rest using keys protected by Android Keystore.
- Keep original filenames and user-visible metadata inside encrypted metadata records; use opaque random IDs for on-disk object names.
- Never expose vault objects through the existing general file-serving endpoints.
- Require an authenticated vault session before listing, previewing, streaming, exporting, or deleting vault items.
- Do not treat the existing PlainApp network encryption or pairing as proof that vault files are encrypted at rest.
- Lock the vault on explicit lock, app/session timeout, and server stop; revoke vault sessions on lock.
- Validate IDs and paths server-side; do not accept arbitrary filesystem paths from browser requests.
- Keep decrypted data in memory or short-lived private temporary files only when necessary, and clean up temporary data on success, failure, cancellation, and app shutdown.
- Do not silently delete originals during the first import implementation.

## Media streaming

The browser should request vault content through a dedicated authenticated endpoint, not through ordinary file paths. The endpoint must decrypt only after authorization and must support browser media range requests if the existing player needs seeking. Chunked authenticated encryption is preferred so each chunk is authenticated before plaintext is released. Avoid a single streaming GCM decryptor that can emit unauthenticated plaintext before the final tag check.

## Delivery sequence

1. Establish VaultSync app identity while leaving the existing Kotlin namespace and upstream modules intact.
2. Add a vault storage engine with Android Keystore-backed keys, opaque object IDs, encrypted metadata, authenticated chunk encryption, and round-trip/tamper tests.
3. Add explicit vault UI for import, list, preview, export, and delete; never change the existing PlainApp file list semantics.
4. Add dedicated server endpoints and a separate vault-session authorization gate. Reuse the existing browser dashboard where possible.
5. Implement authenticated media streaming and byte-range seeking, then verify that normal non-vault browser playback remains unchanged.
6. Add auto-lock/session revocation, error handling, migration/backup policy, and security tests.

## Verification gates

- Build and test on the project's supported Android toolchain.
- Round-trip empty, small, multi-chunk, and large files.
- Corrupted headers, truncated chunks, modified ciphertext, and invalid IDs must fail closed.
- Verify vault ciphertext does not contain the plaintext file or filename.
- Verify every vault API rejects missing, expired, or revoked sessions.
- Verify browser seeking works for encrypted video and audio.
- Regression-test normal PlainApp file browsing and playback.
- Do not claim the vault is production-secure until these checks pass.

## Upstream and licensing

Preserve PlainApp copyright notices, upstream attribution, and AGPL-3.0 obligations. Keep the vault feature isolated so upstream updates can be reviewed and merged with less conflict.
