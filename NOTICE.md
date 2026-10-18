# Third-Party Notices

FossMirrorTV is distributed as a combined work under
[GPL-3.0-only](LICENSE), approved by the maintainer on 2026-10-19. Files with
their own Apache-2.0, MIT, ISC or other notices retain those terms; do not remove
their copyright/license headers. The combined-work license does not relicense
third-party files. Provide complete corresponding source and build scripts
with public APK downloads; see [release gates](docs/PUBLIC_RELEASE.md).

| Component | Source / Version | License / Retained Notice |
| --- | --- | --- |
| PlayFair | UxPlay `462153392f2e30937424922039ff9f0cda5e7b1a`, `lib/playfair/` | [GPLv3 text](docs/protocol/playfair-GPLv3-LICENSE.md); [exact source match](docs/protocol/crypto-source.md#provenance-and-release-gates) |
| Apple ALAC | Pinned source and modifications in [ALAC README](app/src/main/cpp/audio_alac/README.md) | [Apache-2.0](app/src/main/cpp/audio_alac/LICENSE), Apple copyright headers and [notice](app/src/main/assets/licenses/apple-alac-notice.txt) |
| Lucide icons | Icon provenance in [brand notes](design/brand/README.md) | [ISC](app/src/main/assets/licenses/lucide.txt) |
| Bouncy Castle | `org.bouncycastle:bcprov-jdk18on:1.86` | [MIT text from its JAR](app/src/main/assets/licenses/bouncycastle.txt) |
| JmDNS | `org.jmdns:jmdns:3.5.10` | Apache-2.0; retain its upstream notice |
| AndroidX / Material / Kotlin | Resolved versions in the [runtime inventory](docs/dependency-inventory.json) | Apache-2.0; retain applicable notices |
| SLF4J | `org.slf4j:slf4j-api:2.0.7` | [MIT text from its JAR](app/src/main/assets/licenses/slf4j.txt) |

Sources consulted as protocol references are not all linked into this APK.
See [reference integration](docs/protocol/reference-integration.md) for pinned
revisions and the distinction between imported code and behavioral evidence.
AI-generated brand sources and prompts are recorded under `design/brand/`.
Test media is synthetic; fixture provenance is documented beside the assets.
AirPlay, Apple, macOS and Android are their respective owners' marks. This
project is not affiliated with or endorsed by Apple or Google.
