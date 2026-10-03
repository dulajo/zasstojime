# ADR-0016: Čistý PostgreSQL 16

- **Stav:** Přijato
- **Oblast:** Architektura
- **Datum:** 2026-10-02

## Kontext

Objem dat je malý (jednotky GB měsíčně), cache řeší CDN.

## Rozhodnutí

PostgreSQL 16 bez rozšíření. `stop_events` dělené po měsících (deklarativní partitioning).

## Zvažované alternativy

| Alternativa | Proč ne |
|---|---|
| TimescaleDB | Zbytečná složitost. |
| Redis | Cache řeší CDN a paměť serveru. |
