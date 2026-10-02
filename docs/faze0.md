# Fáze 0 – zjištění z reálných dat

Průzkum 2. 10. 2026 kolem 20:15 (večer ve všední den, mimo špičku).

## Zdroje

| Zdroj | Velikost (gzip) | Vozidel | Verdikt |
|---|---|---|---|
| `/v2/vehiclepositions` | 108 kB | 1 038 | Jen jedoucí vozidla (`on_track`, `at_stop`) |
| `/v2/vehiclepositions?includeNotTracking=true` | 269 kB | 2 837 | ✅ **Použít**: všechny stavy včetně dojetých a zrušených |
| `/v2/public/vehiclepositions` | 29 kB | — | ❌ Chybí zpoždění při odjezdu i začátek spoje |
| GTFS-RT `trip_updates.pb` | 1,9 MB | — | ❌ Větší, bez PID stavů vozidla |
| PID GTFS `PID_GTFS.zip` | 50 MB (denně) | — | ✅ `routes.txt` má `is_regional`, `is_night`, `is_substitute_transport` |

Objem při pollingu 30 s: ~270 kB × 2 880 ≈ **0,8 GB/den**.

## Payload

| Zjištění | Dopad |
|---|---|
| Struktura odpovídá předpokladům, `delay` je objekt (`actual`, `last_stop_arrival`, `last_stop_departure`) | Metodika platí |
| `delay.actual` nikdy `null` u `on_track` / `at_stop` | — |
| `last_stop_departure` je `null` u 255 z 420 vozidel `at_stop` | Vozidlo ještě neodjelo; odjezd měřit až po odjezdu |
| `vehicle_registration_number` je `null` u metra a vlaků | Vozidlo jen informativně |
| Žádná poloha starší než 5 min, 73 starších než 60 s | Hranice 5 min stačí |
| 1 `trip_id` hlášené dvěma vozidly | Spřažené soupravy existují, deduplikace nutná |

## Stavy vozidel (`includeNotTracking=true`)

| Stav | Počet | Zpracování |
|---|---|---|
| `after_track` | 704 | Spoj dokončen |
| `on_track` | 618 | Měřit |
| `before_track_delayed` | 586 | Ignorovat (ještě nevyjel) |
| `before_track` | 467 | Ignorovat |
| `at_stop` | 409 | Měřit |
| `after_track_delayed` | 39 | Spoj dokončen |
| `off_track` | 9 | Ignorovat |
| `canceled` | 5 | Spoj zrušen |

## Zpoždění (jedoucí vozidla, jeden snímek)

| Typ | Vozidel | Zpožděných (≥ 180 s) | Max |
|---|---|---|---|
| Tramvaj | 209 | 6,7 % | 4,8 min |
| Metro | 36 | 0 % | 23 s |
| Vlak | 97 | 17,5 % | 19 min |
| Autobus | 681 | 18,6 % | 78 min |
| Trolejbus | 14 | 28,6 % | 4,6 min |

Celkem 15,6 % vozidel ≥ 180 s, 1,3 % jede napřed (≤ −60 s).

## Kvalita dat

| Případ | Hodnoty | Poučení |
|---|---|---|
| Bus 410 → Mimoň | `actual` 4 682 s (78 min), ale `last_stop_departure` 534 s (9 min) | `actual` umí ulétnout; měření na odjezdech (#33) je odolnější |
| Bus 406 → Vlašim | `actual` 1 732 s, `last_stop_departure` 7 s | Totéž; ověřit na záznamu, jestli jde o skok |
| Metro | Zpoždění max. 23 s | Možná dopočet z jízdního řádu (#13) |

## Ještě ověřit na 2h záznamu

- Měření odjezdů: přechod `at_stop` → `on_track` a vyplnění `last_stop_departure`.
- Přechody do `after_track` a zrušení.
- Četnost a podoba skoků v `actual` (kalibrace pravidla #35).
