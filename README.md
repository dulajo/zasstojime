# Trčíme.cz / Zpožděno

Veřejný dashboard dochvilnosti Pražské integrované dopravy (PID) nad otevřenými daty Golemio.
Srozumitelná a obhajitelná čísla: každé zveřejněné číslo jde vysvětlit v metodice.

Zdroj dat: [Golemio](https://golemio.cz), licence CC BY 4.0. Projekt není oficiálním webem PID ani DPP.

## Dokumentace

| Dokument | Obsah |
|---|---|
| [docs/spec.md](docs/spec.md) | **Specifikace Fáze 1: vstupní bod** (požadavky, algoritmus, akceptační kritéria) |
| [docs/adr/](docs/adr/README.md) | Rozhodnutí (ADR), každé v samostatném souboru |
| [docs/metodika.md](docs/metodika.md) | Jak se počítá každé číslo |
| [docs/technicke.md](docs/technicke.md) | Architektura, datový model, API, konfigurace, nasazení |
| [docs/provoz.md](docs/provoz.md) | Nasazení do homelabu, monitoring a alerty, ověření AC-07 a AC-08 |
| [docs/faze0.md](docs/faze0.md) | Zjištění z reálných dat |

## Místní spuštění

```
cp .env.example .env            # vyplnit GOLEMIO_API_KEY a DATABASE_PASSWORD
cd server && ./gradlew build    # testy potřebují Docker (PostgreSQL přes Dev Services)
cd .. && docker compose up -d --build
curl localhost:8080/api/health
```

Podrobnosti v [docs/technicke.md – Vývoj](docs/technicke.md#vývoj). Nasazení do homelabu
(sdílený PostgreSQL, centrální Grafana) popisuje [docs/provoz.md](docs/provoz.md).

První část webu je v [`web/`](web/README.md). Místní vývoj: `cd web && npm ci && npm run dev`;
bez historie se zobrazí stav bez dat, `?demo=1` ukáže jasně označená ukázková čísla.

## Roadmapa

| Fáze | Obsah | Stav |
|---|---|---|
| 0 – Ověření dat | API klíč, záznam dat, porovnání zdrojů, ověření metodiky | ✅ (zbývá ranní špička, metro) |
| 1a – Sběr | GTFS import, sběr, stavový automat spoje, `stop_events`, monitoring, nasazení do homelabu **co nejdřív** (historie začíná spuštěním) | 🚧 nasazeno 2026-10-04 (CT 126), čeká na ověření AC-07 a AC-08 |
| 1b – Výpočty a API | Agregace, žebříčky, „Moje trasa“, API | 🚧 implementováno; čeká na provozní ověření a ladění výkonu |
| 2 – Web | Dashboard (metriky, kategorie, žebříčky, rekordman), **Moje trasa** (od ~1 měsíce dat), metodika, OG obrázky | 🚧 první dashboard a vysvětlení metodiky hotové, náhled v homelabu (`http://10.140.0.126/`); Moje trasa a OG obrázky čekají |
| 3 – Rozšíření | Grafy během dne, detail linky, historie | — |
| Později | Přepočty zpoždění na srozumitelné jednotky času (na webu občas i pivní vtip, na sítích ne); příspěvky na sociální sítě se schvalováním a monetizace | — |

## Otevřené otázky

| # | Otázka |
|---|---|
| 1 | Jsou zpoždění metra reálná? (Fáze 0, #13) |
| 2 | Výsledný název a doména |
| 3 | Co ukazovat po půlnoci, kdy má nový den skoro nulová čísla |
