# Trčíme.cz / Zpožděno

Veřejný dashboard dochvilnosti Pražské integrované dopravy (PID) nad otevřenými daty Golemio.
Srozumitelná a obhajitelná čísla: každé zveřejněné číslo jde vysvětlit v metodice.

Zdroj dat: [Golemio](https://golemio.cz), licence CC BY 4.0. Projekt není oficiálním webem PID ani DPP.

## Dokumentace

| Dokument | Obsah |
|---|---|
| [docs/adr.md](docs/adr.md) | Rozhodnutí: co, proč a co jsme zamítli |
| [docs/metodika.md](docs/metodika.md) | Jak se počítá každé číslo |
| [docs/technicke.md](docs/technicke.md) | Architektura, datový model, API, konfigurace, nasazení |
| [docs/faze0.md](docs/faze0.md) | Zjištění z reálných dat |

## Roadmapa

| Fáze | Obsah | Stav |
|---|---|---|
| 0 – Ověření dat | API klíč, záznam dat, porovnání zdrojů, ověření metodiky | ✅ (zbývá ranní špička, metro) |
| 1a – Sběr | GTFS import, sběr, stavový automat spoje, `stop_events`, monitoring, nasazení do homelabu **co nejdřív** (historie začíná spuštěním) | — |
| 1b – Výpočty a API | Agregace, žebříčky, „Moje trasa“, API | — |
| 2 – Web | Dashboard (metriky, semafor, žebříčky, rekordman), **Moje trasa** (od ~1 měsíce dat), metodika, OG obrázky | — |
| 3 – Rozšíření | Grafy během dne, detail linky, historie | — |
| Později | Přepočty zpoždění na srozumitelné jednotky času (na webu občas i pivní vtip, na sítích ne); příspěvky na sociální sítě se schvalováním a monetizace | — |

## Otevřené otázky

| # | Otázka |
|---|---|
| 1 | Jsou zpoždění metra reálná? (Fáze 0, #13) |
| 2 | Výsledný název a doména |
| 3 | Co ukazovat po půlnoci, kdy má nový den skoro nulová čísla |
