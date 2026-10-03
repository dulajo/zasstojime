# ADR-0001: Zdroj real-time dat: Golemio vehiclepositions

- **Stav:** Přijato
- **Oblast:** Data
- **Datum:** 2026-10-02

## Kontext

Potřebujeme pro každý spoj PID zpoždění při průjezdu zastávkami, začátek spoje a stav vozidla (jede, dojel, zrušen). Golemio nabízí několik endpointů s různým obsahem a velikostí.

## Rozhodnutí

Používáme `GET /v2/vehiclepositions?limit=10000&includeNotTracking=true` s gzipem, dotaz každých 30 s.

## Zvažované alternativy

| Alternativa | Proč ne |
|---|---|
| `/v2/vehiclepositions` bez `includeNotTracking` | Vrací jen jedoucí vozidla; nepoznáme dojetí ani zrušení. |
| `/v2/public/vehiclepositions` (29 kB) | Chybí zpoždění při odjezdu, zastávka i začátek spoje. |
| GTFS-RT `trip_updates.pb` (1,9 MB) | Větší, bez PID stavů vozidla. |

## Důsledky

- ~270 kB na dotaz (gzip), ~2 800 záznamů.
- Interval 30 s zachytí 98,7 % průjezdů zastávkami.
- Závislost na dostupnosti Golemia; výpadky se evidují (ADR-0049, `ingest_runs`).

## Podklady

- [faze0.md – Zdroje](../faze0.md#zdroje)
