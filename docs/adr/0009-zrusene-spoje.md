# ADR-0009: Zrušené spoje mimo index, zobrazují se zvlášť

- **Stav:** Přijato
- **Oblast:** Metodika
- **Datum:** 2026-10-02

## Kontext

Zrušený spoj nemá zpoždění, ale pro cestující je podstatný.

## Rozhodnutí

Spoj ve stavu `canceled` nebo s `is_canceled = true` se nepočítá do indexu dochvilnosti a zobrazuje se jako samostatné číslo.

## Zvažované alternativy

| Alternativa | Proč ne |
|---|---|
| Započítat jako zpožděný | Nemá zpoždění; zkreslilo by průměry. |

## Důsledky

- Záznam: 6 zrušených spojů za 2 h večer, 1 zrušen až za jízdy.
