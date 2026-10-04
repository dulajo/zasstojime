# Web – první část Fáze 2

Next.js (App Router), TypeScript strict, Tailwind a TanStack Query. Úvodní stránka
čte denní přehled, žebříčky a rekordmana z API serveru. Zobrazuje stav bez dat,
chybu spojení a neúplný den. Metro se podle ADR-0013 ve veřejném přehledu
nezobrazuje. Stránka Metodika vysvětluje základní veřejné metriky.

## Místní vývoj

Potřebujete Node.js 20.9+ a běžící server na `http://localhost:8080`. Bez
nasbírané historie server vrací pro denní přehled 404; web ukáže informaci o
chybějících datech. Pro kontrolu vzhledu otevřete `http://localhost:3000/?demo=1`.
Tento režim má **vymyšlená, výrazně označená ukázková data**.

```bash
cd server
APP_JOBS_ENABLED=false ./gradlew quarkusDev

# V druhém terminálu, z kořene repozitáře:
cd web
npm ci
npm run dev
```

Pokud API běží jinde, nastavte `NEXT_PUBLIC_API_BASE_URL` před spuštěním webu,
například v lokálním `.env.local`. Tato veřejná adresa se při produkčním buildu
vloží do statických souborů. Výchozí `localhost:8080` platí jen pro `npm run dev`;
`npm run build` bez nastavené adresy skončí chybou.
Hodnota `/` znamená stejný původ: web volá `/api/…` na adrese, ze které se načetl.
Tak běží náhled v homelabu za nginxem (`deploy/03-web.sh`, docs/provoz.md, krok 5).

## API kontrakt a build

`openapi.json` je snímek schématu serveru a `src/lib/api-schema.ts` se z něj
generuje podle ADR-0024. Po změně odpovědi serveru spusťte s běžícím serverem
`npm run api:refresh`; příkaz ověří přítomnost klíčových schémat a znovu
vygeneruje typy. Aktualizované soubory patří do stejného commitu jako změna API.

```bash
npm run typecheck
NEXT_PUBLIC_API_BASE_URL=http://localhost:8080 npm run build
```

`npm run typecheck` nejdřív vygeneruje typy Next.js (`next-env.d.ts` a
`.next/types`); tyto soubory nejsou v gitu.

Build vytváří statický výstup v `out/`, připravený pro pozdější hosting na
Cloudflare Pages. Web zatím není nasazen. Výběr „Moje trasa“ přijde po
nasbírání historie a doplnění další části Fáze 2.
