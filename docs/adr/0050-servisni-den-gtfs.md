# ADR-0050: Provozní den = servisní den podle PID jízdního řádu

- **Stav:** Přijato · nahrazuje ADR-0004
- **Oblast:** Metodika
- **Datum:** 2026-10-03

## Kontext

PID GTFS vede 1 422 z 87 651 spojů s výjezdem ≥ 24:00 (např. 24:13), tj. patří do předchozího servisního dne. Podle data výjezdu by spoj v 00:13 v sobotu ráno patřil do soboty, ačkoliv jede podle pátečního (pracovního) jízdního řádu. To by rozbilo typ dne v „Moje trasa“.

## Rozhodnutí

Při importu GTFS se pro každý `trip_id` uloží čas výjezdu z jízdního řádu. `service_date` = lokální datum `start_timestamp`, mínus 1 den, pokud jízdní řád uvádí výjezd ≥ 24:00. Spoje, které v GTFS nejsou, berou datum výjezdu.

## Zvažované alternativy

| Alternativa | Proč ne |
|---|---|
| Datum výjezdu (ADR-0004) | Nesedí s typem dne u nočních spojů. |
| Heuristika: výjezdy 00:00–04:00 nočních linek patří k předchozímu dni | Nepřesné; jízdní řád dává přesnou odpověď. |

## Důsledky

- Import GTFS čte i `stop_times.txt` (první zastávka každého spoje).
- Spoj se stále nedělí mezi dva dny.

## Podklady

- Ověřeno v PID_GTFS.zip z 2. 10. 2026.
