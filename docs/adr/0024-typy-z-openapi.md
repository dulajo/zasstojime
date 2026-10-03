# ADR-0024: TS typy generované z OpenAPI

- **Stav:** Přijato
- **Oblast:** Technologie
- **Datum:** 2026-10-02

## Kontext

Typová bezpečnost přes hranici server–klient.

## Rozhodnutí

Server publikuje OpenAPI; klient generuje typy (`openapi-typescript`).

## Zvažované alternativy

| Alternativa | Proč ne |
|---|---|
| Ručně psané typy | Rozjedou se. |

## Důsledky

- Změna API rozbije build frontendu (žádoucí).
