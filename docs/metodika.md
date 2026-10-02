# Metodika

Jak se počítá každé číslo na webu. Slouží jako podklad pro implementaci i pro veřejnou stránku „Metodika“.
Body označené 🔍 se ověří ve Fázi 0 na reálných datech.

## Vstupní data (Golemio `vehiclepositions`) 🔍

| Význam | Předpokládaná cesta v `features[].properties` |
|---|---|
| ID spoje | `trip.gtfs.trip_id` |
| Začátek spoje | `trip.start_timestamp` |
| Linka | `trip.gtfs.route_id`, `trip.gtfs.route_short_name` |
| Typ dopravy | `trip.gtfs.route_type` |
| Směr | `trip.gtfs.trip_headsign` |
| Vozidlo | `trip.vehicle_registration_number` |
| Zpoždění [s] | `last_position.delay.actual` (může být `null`) |
| Čas polohy | `last_position.origin_timestamp` |
| Stav vozidla | `last_position.state_position` |

## Platný snapshot

Snapshot vstupuje do výpočtů, jen když platí **vše**:

| Podmínka | Důvod |
|---|---|
| `delay.actual` ≠ `null` | Bez zpoždění nic nespočítáme |
| `state_position` ∈ {`on_track`, `at_stop`} 🔍 | Mimo trasu nebo před jízdou je zpoždění nespolehlivé |
| `origin_timestamp` je mladší než 5 min | Vozidlo se reálně hlásí |
| `origin_timestamp` je novější než poslední zpracovaný | Deduplikace |

Víc vozidel na jednom spoji (spřažené soupravy) → bere se nejnovější snapshot.

## Hodnoty spoje

| Hodnota | Definice | Použití |
|---|---|---|
| `last_delay` | Poslední platné zpoždění | Živý stav |
| `max_delay` | Max z `min(d[i], d[i-1], d[i-2])`, tj. musí vydržet 3 snapshoty | Součet, rekordman |
| `max_delay_at` | Kdy bylo maximum dosaženo | „+74 min v 16:42“ |
| `final_delay` | `last_delay` při ukončení spoje | Index dochvilnosti |
| `suspect` | `max_delay` > 7200 s 🔍 | Vyloučí z rekordmana |

## Životní cyklus spoje

| Z | Událost | Do |
|---|---|---|
| — | první platný snapshot | `running` |
| `running` | `state_position = after_track` | `finished` |
| `running` | 15 min bez snapshotu | `finished` (timeout) |
| `running` | `state_position = canceled` | `canceled` |

Ostatní stavy (`off_track`, `mismatched`, `before_track`, …) spoj neukončí, jen se nepoužijí pro výpočet. 🔍
Spoj bez jediného platného snapshotu se nepočítá.

## Pásma zpoždění (podle `final_delay`)

| Pásmo | Rozsah |
|---|---|
| Včas | < 180 s (včetně jízdy napřed) |
| Mírné | 180–299 s |
| Výrazné | 300–899 s |
| Kritické | ≥ 900 s |

## Metriky (za provozní den)

| Metrika | Výpočet |
|---|---|
| Index dochvilnosti | `finished` s `final_delay` < 180 / všechny `finished` × 100 |
| Živý stav | Podíl `running` s `last_delay` ≥ 180 |
| Součet zpoždění | Σ max(0, `max_delay`) přes všechny spoje |
| Průměr zpožděného spoje | Průměr `final_delay` u `finished` s `final_delay` ≥ 180 |
| Zrušené spoje | Počet `canceled` |
| Jízdy napřed | Počet `finished` s `final_delay` ≤ −60 s |

## Kategorie (semafor)

| Kategorie | Pravidlo (PID GTFS `routes.txt`) 🔍 |
|---|---|
| `metro` | `route_type = 1` |
| `tram` | `route_type = 0` |
| `bus_city` | `route_type = 3`, ne regionální |
| `bus_regional` | `route_type = 3`, regionální |
| `train` | `route_type = 2` |
| `other` | Trolejbus (11), přívoz (4), lanovka (7) |

Noční linka a náhradní doprava jsou příznaky, ne kategorie.

## Žebříčky

| Žebříček | Výpočet | Podmínka |
|---|---|---|
| Největší součet zpoždění | Σ `max_delay` za linku | — |
| Nejvyšší zpoždění na spoj | Σ `max_delay` / počet spojů | ≥ 20 `finished` |
| Nejhorší dochvilnost | Index dochvilnosti linky | ≥ 20 `finished` |
| Rekordman dne | Spoj s nejvyšším `max_delay` | `suspect = false` |
