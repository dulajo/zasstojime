# ADR-0040: Snímek s klesající sekvencí zastávky je neplatný

- **Stav:** Přijato
- **Oblast:** Metodika
- **Datum:** 2026-10-02

## Kontext

Všech 42 velkých skoků zpoždění v záznamu nastalo při přeskoku zpět na trase (chybné spárování vozidla).

## Rozhodnutí

Pokud `last_stop.sequence` klesne pod dosud nejvyšší hodnotu spoje, snímek se ignoruje.
