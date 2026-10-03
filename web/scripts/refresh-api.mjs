import { writeFile } from "node:fs/promises";
import { spawn } from "node:child_process";

const url = process.env.API_OPENAPI_URL ?? "http://127.0.0.1:8080/q/openapi?format=json";
const response = await fetch(url);
if (!response.ok) throw new Error(`OpenAPI request failed: HTTP ${response.status}`);
const schema = await response.json();
for (const name of ["DayResponse", "LeaderboardResponse", "RecordResponse", "MyRouteResponse"]) {
  if (!schema.components?.schemas?.[name]?.properties) {
    throw new Error(`OpenAPI response schema is missing ${name}`);
  }
}
await writeFile(new URL("../openapi.json", import.meta.url), `${JSON.stringify(schema, null, 2)}\n`);
// Node 20.12+ spouští .cmd soubory na Windows jen přes shell (CVE-2024-27980).
const windows = process.platform === "win32";
const child = spawn(windows ? "npm.cmd" : "npm", ["run", "api:generate"], { stdio: "inherit", shell: windows });
const exitCode = await new Promise((resolve, reject) => {
  child.on("error", reject);
  child.on("exit", (code) => resolve(code));
});
process.exitCode = exitCode ?? 1;
