# ADR-0003: Spoj = provozní den + trip_id

- **Stav:** Přijato
- **Oblast:** Metodika
- **Datum:** 2026-10-02

## Kontext

`trip_id` v PID GTFS je navázané na kalendář jízdního řádu a opakuje se ve všech dnech jeho platnosti.

## Rozhodnutí

Spoj jednoznačně identifikuje dvojice `(service_date, trip_id)`.

## Zvažované alternativy

| Alternativa | Proč ne |
|---|---|
| Samotné `trip_id` | Sloučilo by stejný spoj z různých dní. |

## Důsledky

- Všechny tabulky na úrovni spoje mají `service_date` v primárním klíči.
