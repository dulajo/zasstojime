# ADR-0014: Server = jedna aplikace (sběr + API)

- **Stav:** Přijato
- **Oblast:** Architektura
- **Datum:** 2026-10-02

## Kontext

Jeden vývojář, homelab, malý objem dat.

## Rozhodnutí

Sběr, výpočty a API běží v jednom procesu. Kód je rozdělený do modulů `ingest`, `gtfs`, `aggregation`, `api`, aby šly později oddělit.

## Zvažované alternativy

| Alternativa | Proč ne |
|---|---|
| Oddělené služby | Víc věcí k nasazení a hlídání bez přínosu. |

## Důsledky

- Restart aplikace přeruší sběr (krátce); stav se obnoví z DB.
