import nextEnv from "@next/env";

// Adresa API se při buildu vloží do statického výstupu; bez ní by web volal localhost návštěvníka.
// "/" znamená stejný původ: web a API za jedním nginxem (náhled v homelabu, docs/provoz.md).
nextEnv.loadEnvConfig(process.cwd(), false);
const value = process.env.NEXT_PUBLIC_API_BASE_URL;
if (!value) {
  console.error(
    "Chybí NEXT_PUBLIC_API_BASE_URL. Nastavte veřejnou adresu API (pro místní build např. http://localhost:8080, " +
      "pro web a API na stejné adrese \"/\").",
  );
  process.exit(1);
}
if (value !== "/") {
  try {
    new URL(value);
  } catch {
    console.error(`NEXT_PUBLIC_API_BASE_URL není platná URL ani "/": ${value}`);
    process.exit(1);
  }
}
