# ADR-0038: Živá čísla v paměti, do DB jen změny

- **Stav:** Přijato
- **Oblast:** Architektura
- **Datum:** 2026-10-02

## Kontext

Přepočítávat DB agregáty každých 30 s je zbytečné.

## Rozhodnutí

Server drží dnešní spoje v paměti a z nich počítá živé odpovědi. Do DB zapisuje jen změněné spoje a nové průjezdy; denní souhrny každých 5 min a na konci dne.

## Zvažované alternativy

| Alternativa | Proč ne |
|---|---|
| Přepočet DB agregátů v každém cyklu | Zbytečné zápisy. |

## Důsledky

- Po restartu se stav obnoví z `trips_daily` a `stop_events` dnešního dne.
