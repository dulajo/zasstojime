# ADR-0047: Moje trasa: přímé spoje, hledání podle plánovaného času

- **Stav:** Nahrazeno ADR-0058
- **Oblast:** Metodika
- **Datum:** 2026-10-03

## Kontext

`trip_id` se mění se změnou jízdního řádu (14. 12.); uživatel myslí „vlak v 7:52“.

## Rozhodnutí

Spoj se v každém dni hledá podle linky, uzlu nástupu, plánovaného času odjezdu (HH:MM) a typu dne. Jen přímé spoje.

## Zvažované alternativy

| Alternativa | Proč ne |
|---|---|
| Podle `trip_id` | Rozpadne se po změně JŘ. |
| Přestupy | Odloženo. |

## Důsledky

- Po změně JŘ může být „můj spoj“ v jiném čase; UI nabídne nejbližší.
