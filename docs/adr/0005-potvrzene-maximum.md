# ADR-0005: Maximum zpoždění platné po 3 snímcích

- **Stav:** Nahrazeno ADR-0039
- **Oblast:** Metodika
- **Datum:** 2026-10-02

## Kontext

Ochrana proti jednorázovým výkyvům `delay.actual`.

## Rozhodnutí

Maximum zpoždění spoje platí, až když vydrží 3 po sobě jdoucí platné snímky.

## Důsledky

- Záznam ukázal, že `delay.actual` umí ulétnout stabilně, takže pravidlo nestačí. Viz ADR-0039.
