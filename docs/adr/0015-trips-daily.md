# ADR-0015: Hlavní tabulka na úrovni spoje: trips_daily

- **Stav:** Přijato
- **Oblast:** Architektura
- **Datum:** 2026-10-02

## Kontext

Ukládat každý snímek by znamenalo miliony téměř stejných řádků denně.

## Rozhodnutí

Stav spoje se drží v jednom řádku `trips_daily` (spoj × den), aktualizovaném UPSERTem. Detail zastávek viz ADR-0044.

## Zvažované alternativy

| Alternativa | Proč ne |
|---|---|
| Tabulka všech snímků | Miliony řádků denně, většina beze změny. |
