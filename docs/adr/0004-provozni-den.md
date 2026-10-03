# ADR-0004: Provozní den = datum výjezdu spoje

- **Stav:** Přijato
- **Oblast:** Metodika
- **Datum:** 2026-10-02

## Kontext

Noční spoje přejíždějí půlnoc. Je potřeba určit, do kterého dne patří.

## Rozhodnutí

`service_date` = lokální datum (Europe/Prague) z `trip.start_timestamp`. Celý spoj patří do dne svého výjezdu.

## Zvažované alternativy

| Alternativa | Proč ne |
|---|---|
| Datum snímku | Spoj by se rozdělil do dvou dní. |

## Důsledky

- Výpočty musí správně zvládnout změnu času (DST); testy na 25 h a 23 h den.
