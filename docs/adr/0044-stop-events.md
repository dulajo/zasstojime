# ADR-0044: Ukládat každý průjezd zastávkou

- **Stav:** Přijato
- **Oblast:** Architektura
- **Datum:** 2026-10-03

## Kontext

„Moje trasa“ potřebuje zpoždění konkrétního spoje v konkrétní stanici v každém dni. Souhrny za spoj to neumožní.

## Rozhodnutí

Tabulka `stop_events`: spoj, zastávka, stanice (uzel), plánovaný příjezd a odjezd, zpoždění příjezdu a odjezdu. Oddíly po měsících, retence 13 měsíců.

## Zvažované alternativy

| Alternativa | Proč ne |
|---|---|
| Jen souhrny za spoj | Neumožní dotaz na trasu. |

## Důsledky

- ~0,5 mil. řádků/den, ~2 GB/měsíc.
- Plánované časy z `last_stop.arrival_time` / `departure_time`.
