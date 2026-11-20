// Aperçu du style hors ligne sans téléphone : MapLibre GL JS dans un navigateur sans écran (Playwright),
// alimenté par de vrais packs PMTiles locaux. Sert à régler les couleurs et les couches.
//
//   node apercu.mjs <dossier des packs> <suffixe des packs> <dossier de sortie> [clair|sombre] lat,lon,zoom ...
//   ex. node apercu.mjs /chemin/packs annecy /chemin/sortie clair 45.899,6.129,13
import { createReadStream, readFileSync, statSync, mkdirSync } from "node:fs";
import { createServer } from "node:http";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const { chromium } = require("playwright");

const [dossier, suffixe, sortie, theme, ...vues] = process.argv.slice(2);
const ICI = dirname(fileURLToPath(import.meta.url));
const style = JSON.parse(readFileSync(join(ICI, `../../app/src/main/assets/hors-ligne/style-${theme}.json`), "utf8"));
const PORT = 8765;
const base = `http://localhost:${PORT}`;

// Dans l'aperçu, les tuiles viennent directement des fichiers PMTiles ; polices et icônes du site de Protomaps.
style.sources.fond = { type: "vector", url: `pmtiles://${base}/fond-${suffixe}.pmtiles` };
style.sources.velo = { type: "vector", url: `pmtiles://${base}/velo-${suffixe}.pmtiles` };
style.sources.relief = { type: "raster-dem", url: `pmtiles://${base}/relief-${suffixe}.pmtiles`, encoding: "terrarium", tileSize: 512 };
style.glyphs = "https://protomaps.github.io/basemaps-assets/fonts/{fontstack}/{range}.pbf";
style.sprite = `https://protomaps.github.io/basemaps-assets/sprites/v4/${theme === "sombre" ? "dark" : "light"}`;

const page = (vue) => `<!doctype html><html><head><meta charset="utf-8">
<link rel="stylesheet" href="https://unpkg.com/maplibre-gl@5/dist/maplibre-gl.css">
<style>html,body,#carte{margin:0;height:100%;width:100%}</style></head><body><div id="carte"></div>
<script src="https://unpkg.com/maplibre-gl@5/dist/maplibre-gl.js"></script>
<script src="https://unpkg.com/pmtiles@4/dist/pmtiles.js"></script>
<script>
  const protocole = new pmtiles.Protocol();
  maplibregl.addProtocol("pmtiles", protocole.tile);
  const carte = new maplibregl.Map({ container: "carte", style: ${JSON.stringify(style)}, center: [${vue.lon}, ${vue.lat}], zoom: ${vue.zoom}, attributionControl: false });
  window.erreurs = [];
  carte.on("error", (e) => window.erreurs.push(String(e.error && e.error.message || e)));
  carte.on("idle", () => { window.pret = true; });
</script></body></html>`;

const serveur = createServer((req, res) => {
  const url = new URL(req.url, base);
  if (url.pathname === "/page") {
    const [lat, lon, zoom] = url.searchParams.get("vue").split(",").map(Number);
    res.writeHead(200, { "content-type": "text/html; charset=utf-8" });
    return res.end(page({ lat, lon, zoom }));
  }
  const fichier = join(dossier, url.pathname.replace(/^\/+/, ""));
  let taille;
  try { taille = statSync(fichier).size; } catch { res.writeHead(404); return res.end(); }
  const plage = /bytes=(\d+)-(\d*)/.exec(req.headers.range || "");
  const debut = plage ? Number(plage[1]) : 0;
  const fin = plage && plage[2] ? Math.min(Number(plage[2]), taille - 1) : taille - 1;
  res.writeHead(plage ? 206 : 200, {
    "content-type": "application/octet-stream", "accept-ranges": "bytes", "access-control-allow-origin": "*",
    "content-length": fin - debut + 1, ...(plage ? { "content-range": `bytes ${debut}-${fin}/${taille}` } : {}),
  });
  createReadStream(fichier, { start: debut, end: fin }).pipe(res);
});

mkdirSync(sortie, { recursive: true });
serveur.listen(PORT, async () => {
  const navigateur = await chromium.launch({ args: ["--use-gl=angle", "--use-angle=swiftshader", "--enable-unsafe-swiftshader", "--ignore-gpu-blocklist"] });
  const onglet = await navigateur.newPage({ viewport: { width: 411, height: 760 }, deviceScaleFactor: 2 });
  for (const vue of vues) {
    await onglet.goto(`${base}/page?vue=${vue}`);
    try { await onglet.waitForFunction(() => window.pret === true, null, { timeout: 60000 }); } catch { console.log("carte pas au repos après 60 s :", vue); }
    await onglet.waitForTimeout(800);
    const fichier = join(sortie, `${suffixe}-${theme}-${vue.replace(/,/g, "_")}.png`);
    await onglet.screenshot({ path: fichier });
    const erreurs = await onglet.evaluate(() => window.erreurs.slice(0, 5));
    console.log(fichier, erreurs.length ? "ERREURS : " + JSON.stringify(erreurs) : "ok");
  }
  await navigateur.close();
  serveur.close();
});
