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
const command = process.platform === "win32" ? "npm.cmd" : "npm";
const child = spawn(command, ["run", "api:generate"], { stdio: "inherit" });
const exitCode = await new Promise((resolve, reject) => {
  child.on("error", reject);
  child.on("exit", (code) => resolve(code));
});
process.exitCode = exitCode ?? 1;
