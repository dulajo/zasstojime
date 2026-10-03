# ADR-0007: Hranice „včas“ = zpoždění pod 180 s

- **Stav:** Přijato
- **Oblast:** Metodika
- **Datum:** 2026-10-02

## Kontext

Potřebujeme jednoznačnou hranici dochvilnosti.

## Rozhodnutí

Odjezd je zpožděný od +180 s. Spolu s ADR-0034 platí: včas = −59 až +179 s.

## Důsledky

- Pásma: mírné 180–299 s, výrazné 300–899 s, kritické ≥ 900 s.
