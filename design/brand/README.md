# FossMirrorTV Brand Assets

Original assets were generated on 2026-10-11. The current icon and TV banner
were generated with the built-in image-generation tool on 2026-10-16. These are
bitmap assets used by the actual native Android UI, not screenshots claiming
Mac mirroring success.

## Files

- `fossmirrortv-icon-v2.png`: current opaque adaptive/legacy launcher icon.
- `receiver-scene.png`: full-bleed idle-screen artwork.
- `tv-banner-v2.png`: current matching 16:9 launcher banner with the app name.
- `fossmirrortv-icon.png` and `tv-banner.png`: preserved original artwork.
- Runtime WebP files live in `app/src/main/res/drawable-nodpi/`.
- `scripts/prepare-brand-assets.cjs` performs only deterministic resizing and
  compression using Node.js and Sharp. It does not regenerate the artwork.

## Current Final Prompts

### Icon V2

Use case: logo-brand. Asset type: final production Android / Android TV launcher icon for FossMirrorTV, a Mac screen-mirroring receiver. Create one original, polished square raster icon. Solid near-black charcoal #101313 background edge to edge, no outer tile frame. One bold compact geometric symbol: two overlapping warm-white screen outlines, larger landscape display upper-left and a smaller display lower-right; connect them with one folded vivid citron ribbon, and use only two tiny pale-cyan reflection accents. Make the geometry exceptionally clean and balanced with thick readable strokes, flat solid fills, continuous crisp edges and no visual noise. Center all meaningful symbol content in the middle 58 percent of the canvas, leaving generous charcoal padding so circular Android adaptive masks cannot cut it off. No letters, words, typography, gradients, shine, glow, shadow, photographic scene, mockup device, Apple logo, AirPlay logo, or watermark. It should look confident and distinctive at 48 pixels. Output a single square icon, not a contact sheet or logo variations.

### TV Banner V2

Reference: `fossmirrortv-icon-v2.png`, inspected before generation. Built-in tool mode, not the API/CLI fallback.

Use case: logo-brand. Create the matching final Android TV launcher banner for FossMirrorTV. The attached square icon is the exact brand symbol reference: preserve its two offset warm-white screen outlines, citron folded connector, and pale-cyan reflection strokes. Make a wide 16:9 landscape raster banner, designed to remain readable when displayed at 320 by 180 pixels. Edge-to-edge near-black charcoal #101313 background. On the left place the exact text "FossMirrorTV" in one line in a bold clean warm-white sans-serif with comfortable letter spacing; on the right place the reference icon symbol at a balanced readable size. Keep both elements inside generous safe margins; the full word must fit and no letter may touch the symbol. One short citron underline below the name, no other words or labels. Very crisp refined graphic geometry, flat colors, restrained cyan accent, high contrast. No device mockup, screenshot, contact sheet, external tile frame, gradients, glow, decorative blobs, Apple logo, AirPlay logo, watermark, or photographic scene. Output just one finished banner.

## Final Prompts

### Icon

Use case: logo-brand. Production Android TV app icon, square, opaque solid background #101313. Create one original symbol for FossMirrorTV: two thick clean warm-white rounded rectangular screen outlines, a larger one above-left and a smaller one below-right, connected by a bold citron diagonal folded ribbon. Simple two-tone citron fold, two tiny pale-cyan reflection strokes in each screen. Flat digital graphic with perfectly smooth continuous vector-like edges, solid colors, no gradients, no grain, no sketching, no distress, no noise. Center the compact symbol inside the central 58 percent of the image, huge uncluttered surrounding charcoal space for adaptive circular icon cropping. The screen interiors are the SAME solid charcoal as the background, not transparent, no texture. NO transparent background, no tile border, no text, no shadows, no Apple icon, no watermark. The mark should feel distinctive, clean, lively, and readable at tiny sizes.

### Receiver Scene

Use case: stylized-concept. Asset type: production 16:9 full-bleed background for an Android TV screen mirroring receiver, 1920x1080 landscape composition. Primary request: a premium, quiet and vivid digital-cinema waiting screen. Scene: crisp studio still life of two offset thin silver-framed glass screen planes on the RIGHT HALF, connected by a sculptural citron folded ribbon echoing a screen-mirroring symbol. Their glass faces reflect a sharp pale-cyan sky and a small terracotta-red architectural surface; these are actual rectilinear screen objects, not glowing abstract orbs. One larger widescreen plane and one small floating screen plane, careful realistic material and edge detail, sparse and sophisticated, not stock photo. Background: near-black charcoal with subtle fine texture. Keep LEFT 52 PERCENT essentially empty dark charcoal for real native app text and controls; keep upper and lower margins calm. Lighting: precise soft studio light, silver highlights, legible geometry, restrained citron accent, small red/cyan counterpoints. No text, no typography, no UI controls, no cards, no interface mockup, no people, no gradients, no orbs, no bokeh, no purple or navy, no watermark. A clean uncluttered bitmap asset that can sit behind a real usable TV interface; do not draw a laptop keyboard or branded device.

### TV Banner

Create a polished 16:9 Android TV launcher banner for FossMirrorTV. Solid
near-black charcoal background. On the left, large exact text 'FossMirrorTV'
in warm-white modern sans-serif, single line, readable at 320x180. A small
citron accent line below; no subtitle. On the right, two offset white screen
outlines connected by a citron fold and pale-cyan reflection strokes. Generous
margins. No other text, UI, gradients, glow, Apple/AirPlay logo, or watermark.

## Icons

Control vectors adapt Lucide `rotate-cw`, `power`, and `x`. License notices are
bundled in `app/src/main/assets/licenses/lucide.txt`.
