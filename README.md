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

## Roadmapa

| Fáze | Obsah | Stav |
|---|---|---|
| 0 – Ověření dat | API klíč, síť, nahrát 2–3 h payloadů, ověřit body 🔍 v metodice | ⏳ |
| 1 – Pohon | GTFS import, sběr, stavový automat spoje, agregace, API, monitoring, testy | — |
| 2 – Dashboard | Web: hlavní metriky, semafor, žebříčky, rekordman, metodika, OG obrázky | — |
| 3 – Rozšíření | Grafy během dne, detail linky, historie | — |
| Později | Přepočty zpoždění na srozumitelné jednotky času (na webu občas i pivní vtip, na sítích ne); příspěvky na sociální sítě se schvalováním a monetizace | — |

## Otevřené otázky

| # | Otázka |
|---|---|
| 1 | Struktura payloadu, stavy vozidel, pokrytí metra a vlaků (Fáze 0) |
| 2 | Výsledný název a doména |
| 3 | Co ukazovat po půlnoci, kdy má nový den skoro nulová čísla |
