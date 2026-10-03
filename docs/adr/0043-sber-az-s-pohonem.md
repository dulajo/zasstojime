# ADR-0043: Sběr se spustí až s hotovým pohonem

- **Stav:** Přijato
- **Oblast:** Provoz
- **Datum:** 2026-10-03

## Kontext

Alternativou bylo spustit dočasný archivátor surových snímků hned a později je přehrát.

## Rozhodnutí

Žádný dočasný archivátor. Historie začne dnem spuštění sběru.

## Zvažované alternativy

| Alternativa | Proč ne |
|---|---|
| Archivovat snímky už teď | Další komponenta k provozu; autor ji nechce. |

## Důsledky

- „Moje trasa“ bude mít smysluplná čísla ~1 měsíc po spuštění.
- Motivuje nasadit sběr co nejdřív (ADR-0049).
