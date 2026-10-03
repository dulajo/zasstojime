# ADR-0039: Všechna zpoždění spoje se berou z odjezdů, ne z delay.actual

- **Stav:** Přijato · nahrazuje ADR-0005, ADR-0036, ADR-0037
- **Oblast:** Metodika
- **Datum:** 2026-10-02

## Kontext

Záznam: `delay.actual` ulétl stabilně o desítky minut (bus 410: 78 min proti 9 min na odjezdech). Pravidlo 3 snímků to nezachytí.

## Rozhodnutí

Maximum, živý stav i rekordman se počítají ze změřených odjezdů (`delay.last_stop_departure`). `delay.actual` se do výpočtů nepoužívá.

## Zvažované alternativy

| Alternativa | Proč ne |
|---|---|
| Maximum z `delay.actual` s potvrzením 3 snímky (ADR-0005) | Falešní rekordmani 78, 35 a 34 min. |

## Důsledky

- Rekordman nese i zastávku („+74 min v 16:42 z Florence“).
- Odpadá klouzavé okno snímků.

## Podklady

- [faze0.md – 2h záznam](../faze0.md)
