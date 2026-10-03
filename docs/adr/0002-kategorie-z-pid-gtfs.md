# ADR-0002: Kategorie linek z PID GTFS

- **Stav:** Přijato
- **Oblast:** Data
- **Datum:** 2026-10-02

## Kontext

Dashboard dělí linky na metro, tramvaje, městské a regionální autobusy, vlaky a ostatní. Standardní GTFS rozlišuje jen typ dopravy (`route_type`).

## Rozhodnutí

Kategorie se určí z PID GTFS `routes.txt`: `route_type` + PID sloupce `is_regional`, `is_night`, `is_substitute_transport`. Import každou noc.

## Zvažované alternativy

| Alternativa | Proč ne |
|---|---|
| Podle čísla linky (100–299 městské, 300+ regionální) | Ověřeno, že nefunguje: městské linky mají i čísla 5xx a 9xx, trolejbus 275 by vyšel jako autobus. |

## Důsledky

- Noční linka a náhradní doprava jsou příznaky, ne kategorie.
- Nová linka v jízdním řádu se zařadí automaticky.

## Podklady

- [faze0.md – Zdroje](../faze0.md#zdroje)
