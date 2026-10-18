# Privacy

FossMirrorTV receives screen video and audio from a sender over the local
network and plays them through Android MediaCodec and AudioTrack. DNS-SD/mDNS
advertises a device name, installation identifier, public key and capabilities
to nearby network participants. The app has no analytics, account service,
cloud media upload, or automatic update checker in its current source.

Receiver keys are generated on the device and kept in app-private no-backup
storage. They are not Android Keystore-backed on every supported Android
version. An installation identifier is persisted locally to keep the advertised
name stable. Clearing app data or uninstalling removes that identity.

Logs contain operational metadata such as LAN addresses, receiver/session names,
codec details, timing, counters, and errors. Treat logs as private and redact
them before sharing. Android/platform diagnostics have their own behavior.
The app does not normally save the received screen or audio to files.

Debug builds have an explicitly triggered, one-shot decrypted IDR diagnostic
capture in app-private cache. It requires a local cache marker and is disabled
when the app is not debuggable. A captured frame can contain private screen
content and remains until removed or the cache is cleared. Do not distribute
debug builds as the general public download.

Temporary buffer clearing is best effort, not a guarantee that JVM, decoder,
graphics, or OS copies can be securely erased. There is no PIN or trusted-device
authorization; use only a trusted LAN. See [Security](SECURITY.md).
