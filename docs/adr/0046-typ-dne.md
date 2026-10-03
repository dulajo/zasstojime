# ADR-0046: Typ dne s českými svátky

- **Stav:** Přijato
- **Oblast:** Metodika
- **Datum:** 2026-10-03

## Kontext

„Můj vlak v pracovní den“ nesmí započítat svátek.

## Rozhodnutí

Tabulka `calendar_days`: `workday`, `saturday`, `sunday_holiday`; svátky včetně Velikonoc počítá server.
