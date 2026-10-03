# ADR-0049: Fáze 1 se nasazuje po částech

- **Stav:** Přijato
- **Oblast:** Provoz
- **Datum:** 2026-10-03

## Kontext

Historie začíná dnem spuštění sběru (ADR-0043).

## Rozhodnutí

Fáze 1a (import GTFS, sběr, `trips_daily`, `stop_events`, monitoring) se nasadí co nejdřív. Fáze 1b (souhrny, žebříčky, Moje trasa, API) potom.

## Zvažované alternativy

| Alternativa | Proč ne |
|---|---|
| Nasadit až celý pohon | Ztratíme týdny historie. |
