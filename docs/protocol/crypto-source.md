# Crypto Source Notes

## Boundaries

The active receiver uses Bouncy Castle lightweight Ed25519/X25519 primitives
for non-PIN legacy pairing and JCA ciphers/digests for session derivation and
transport encryption. It does not register a process-wide security provider.
Device signing keys are generated once and stored in app-private no-backup
storage; Android Keystore-backed Ed25519 is not claimed on API 28.

`NativeFairPlaySession` owns one C++ `FairPlaySession` per RTSP connection. JNI
calls the existing PlayFair implementation under
`app/src/main/cpp/airplay_crypto/playfair/`. No new cryptographic primitive or
placeholder FairPlay implementation was invented for the rewrite.

## Implemented Paths

- Non-PIN pairing uses real signatures and ephemeral shared-secret verification.
  The verified phase-two response can legitimately have an empty body; this is
  not an unimplemented handshake returning success.
- FairPlay validates request framing, version, phase ordering, and mode bounds
  before invoking PlayFair. SETUP consumes its 72-byte encrypted key and 16-byte
  IV, and refuses to start media before the required handshake exists.
- Video derives stream-specific key/IV material from the session key and the
  unsigned decimal stream connection ID. CTR state continues across packets,
  including while decoded frames are dropped or the surface is detached.
- Audio decrypts complete CBC blocks per packet with a reset IV, retaining the
  clear trailing bytes. Decryption precedes media parsing/decoding.
- Session teardown clears owned secret buffers where practical. Complete
  erasure of JVM/provider-internal key copies is not guaranteed.

## Behavioral References

The implementation was checked against these primary reference files:

- [pairing.c](https://github.com/FDH2/UxPlay/blob/master/lib/pairing.c): legacy
  handshake, signed verification, and pairing cipher continuity.
- [crypto.c](https://github.com/FDH2/UxPlay/blob/master/lib/crypto.c): SHA-512 and
  library-backed crypto operations.
- [raop_handlers.h](https://github.com/FDH2/UxPlay/blob/master/lib/raop_handlers.h):
  typed SETUP negotiation and optional verified shared-secret key derivation.
- [mirror_buffer.c](https://github.com/FDH2/UxPlay/blob/master/lib/mirror_buffer.c):
  stream key/IV salts and continuous mirror decryption.
- [raop_buffer.c](https://github.com/FDH2/UxPlay/blob/master/lib/raop_buffer.c):
  packet-local audio decryption and clear remainder.
- [fairplay_playfair.c](https://github.com/FDH2/UxPlay/blob/master/lib/fairplay_playfair.c)
  and [PlayFair](https://github.com/FDH2/UxPlay/tree/master/lib/playfair): native
  FairPlay response/key implementation lineage.

Host tests exercise known-answer primitive vectors, independent crypto
operations, invalid signatures, chunk boundaries, and lifecycle rejection.
Native tests exercise FairPlay framing and state. **They do not independently
prove a captured FairPlay encrypted key decrypts correctly.** Android provider
behavior and real macOS interoperability need separate runtime evidence; see
[Testing](../TESTING.md) for the later device results and remaining gaps.

## Provenance And Release Gates

PlayFair was already present in this repository. On 2026-10-18, all seven
vendored files were compared by Git blob ID with UxPlay commit
`462153392f2e30937424922039ff9f0cda5e7b1a`, under `lib/playfair/`.
Every file matches byte for byte. The retained GPLv3 text also has the same
blob ID as that snapshot's `LICENSE.md`. This establishes a reproducible
source match, not which earlier copy introduced the files or a cryptographic
audit. No vendor implementation changes were made in this publication audit.

| File | Matching Git Blob ID |
| --- | --- |
| `hand_garble.c` | `8e8361b42b177e892f0efe3cb993e34c5cbea0c2` |
| `modified_md5.c` | `3758a33e018fe3eced10c0d5bf144e9cc27e2276` |
| `omg_hax.c` | `96f79a9f25cc41a8c82f77d6a45f878746a7528a` |
| `omg_hax.h` | `d6f19d316666944decc87b57fc5432ab8213f221` |
| `playfair.c` | `782c17e0b5711e20c12f8c64947749664fa1592b` |
| `playfair.h` | `5909a99afa8dca8a0e3878126fe046c60688d52f` |
| `sap_hash.c` | `9e7ae851de3c9cca4160293c0e8f378727c0aa42` |

An independently validated, captured encrypted-key known-answer fixture
remains a crypto test gap. Actual Mac video/audio interoperability was later
confirmed on one TV, as recorded in [Testing](../TESTING.md); this does not
replace that independent key test or a security review.

Earlier notes proposed GPL-3.0-or-later. The maintainer approved a GPLv3 public
release on 2026-10-19, and the root [LICENSE](../../LICENSE) now covers the
combined work as GPL-3.0-only. No additional "or later" grant is inferred for
third-party files. Preserve imported notices:
the UxPlay wrapper has LGPL notices and PlayFair carries GPLv3 obligations.
The full [GPLv3 text](playfair-GPLv3-LICENSE.md) is retained. Distribution
compliance and complete attribution still require review; this note is not
legal clearance for proprietary distribution.
