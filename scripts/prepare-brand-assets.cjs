const path = require("node:path");
const fs = require("node:fs/promises");
const sharp = require("sharp");

async function main() {
  const root = path.resolve(__dirname, "..");
  const sources = path.join(root, "design", "brand");
  const output = path.join(root, "app", "src", "main", "res", "drawable-nodpi");
  await fs.mkdir(output, { recursive: true });
  for (const [source, name, width, height, quality] of [
    ["fossmirrortv-icon-v2.png", "foss_mirror_mark", 512, 512, 90],
    ["receiver-scene.png", "receiver_scene", 1600, 900, 82],
    ["tv-banner-v2.png", "receiver_tv_banner", 320, 180, 90],
  ]) {
    await sharp(path.join(sources, source)).resize(width, height, { fit: "cover" })
      .webp({ quality }).toFile(path.join(output, `${name}.webp`));
  }
  for (const [density, size] of [["mdpi", 48], ["hdpi", 72], ["xhdpi", 96], ["xxhdpi", 144], ["xxxhdpi", 192]]) {
    const folder = path.join(root, "app", "src", "main", "res", `mipmap-${density}`);
    await fs.mkdir(folder, { recursive: true });
    const icon = await sharp(path.join(sources, "fossmirrortv-icon-v2.png"))
      .resize(size, size).webp({ quality: 90 }).toBuffer();
    for (const name of ["ic_launcher.webp", "ic_launcher_round.webp"]) await fs.writeFile(path.join(folder, name), icon);
  }
}

main().catch((error) => { console.error(error); process.exitCode = 1; });
