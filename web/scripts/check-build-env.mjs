import nextEnv from "@next/env";

// Adresa API se při buildu vloží do statického výstupu; bez ní by web volal localhost návštěvníka.
nextEnv.loadEnvConfig(process.cwd(), false);
const value = process.env.NEXT_PUBLIC_API_BASE_URL;
if (!value) {
  console.error("Chybí NEXT_PUBLIC_API_BASE_URL. Nastavte veřejnou adresu API (pro místní build např. http://localhost:8080).");
  process.exit(1);
}
try {
  new URL(value);
} catch {
  console.error(`NEXT_PUBLIC_API_BASE_URL není platná URL: ${value}`);
  process.exit(1);
}
