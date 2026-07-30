import { readFile } from "node:fs/promises";
import { PARTS, PURCHASABLE_PART_IDS } from "./src/catalog.js";

const [html, model, app] = await Promise.all([
  readFile(new URL("./index.html", import.meta.url), "utf8"),
  readFile(new URL("./src/model.js", import.meta.url), "utf8"),
  readFile(new URL("./src/app.js", import.meta.url), "utf8"),
]);

const failures = [];
const ids = Object.keys(PARTS);

if (new Set(ids).size !== ids.length) failures.push("Component identifiers must be unique.");
if (PURCHASABLE_PART_IDS.length !== ids.length) failures.push("Every modeled component group must be purchasable.");

for (const [id, part] of Object.entries(PARTS)) {
  if (!part.name || !part.color || !part.url) failures.push(`${id} has incomplete catalog data.`);
  if (!/^https:\/\//.test(part.url)) failures.push(`${id} does not use an HTTPS recommendation URL.`);
  if (!model.includes(`"${id}"`)) failures.push(`${id} is not represented in the model.`);
}

const body = html.match(/<body[\s\S]*?<\/body>/i)?.[0] ?? "";
const visibleText = body
  .replace(/<svg[\s\S]*?<\/svg>/gi, "")
  .replace(/<script[\s\S]*?<\/script>/gi, "")
  .replace(/<[^>]+>/g, "")
  .replace(/\s+/g, "");
if (visibleText) failures.push("The page body contains visible text.");

for (const selector of ["#scene", "#toolbar", "#progress", "#part-actions"]) {
  if (!html.includes(selector.slice(1))) failures.push(`Missing interface element ${selector}.`);
}

for (const capability of ["OrbitControls", "OutlinePass", "setXray", "state.purchased", "localStorage", "dblclick"]) {
  if (!app.includes(capability)) failures.push(`Missing interaction capability ${capability}.`);
}

if (failures.length) {
  console.error(failures.join("\n"));
  process.exitCode = 1;
} else {
  console.log(`${ids.length} modeled component groups validated.`);
}
