# ADR-0022: Testy proti skutečnému PostgreSQL

- **Stav:** Přijato
- **Oblast:** Technologie
- **Datum:** 2026-10-02

## Kontext

Mocky nebo H2 nezachytí chyby v SQL a partitioningu.

## Rozhodnutí

Integrační testy běží proti PostgreSQL z Dev Services / Testcontainers.

## Zvažované alternativy

| Alternativa | Proč ne |
|---|---|
| H2 / mocky | Jiný dialekt SQL, falešná jistota. |

## Důsledky

- Testy potřebují Docker.
