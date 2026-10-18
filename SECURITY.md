# Security

FossMirrorTV is experimental receiver software, not a hardened network service.
Use it only on a trusted home LAN. Do not port-forward it, expose it online, or
run it on public/guest networks. Legacy pairing verifies protocol exchanges;
it is not user authorization. PINs and trusted-device access control are not
implemented. Another reachable sender can attempt a connection.

## Reporting

Do not post credentials, private keys, desktop frames, packet captures, or
unredacted logcat/Mac logs in a public issue. Use the repository's private
vulnerability reporting channel if it is enabled. Otherwise open an issue
asking for a private reporting channel, without exploit details or attachments.
There is no promised response SLA or supported stable release yet.

## Release Hygiene

- Keep signing keys/passwords outside the checkout. Never attach a keystore.
- Release APKs must not be debuggable; debug APKs are development-only.
- Offer corresponding source, build instructions, and component notices with APKs.
- Re-run secret and dependency scans for every published revision and asset.
- A clean scan is evidence, not proof that every vulnerability or secret is absent.

See [public-release preparation](docs/PUBLIC_RELEASE.md) and [privacy](PRIVACY.md).
