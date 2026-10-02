# Metodika

Jak se počítá každé číslo na webu. Slouží jako podklad pro implementaci i pro veřejnou stránku „Metodika“.
Body označené 🔍 se ověří ve Fázi 0 na reálných datech.

## Princip

Dochvilnost se měří **na každém odjezdu ze zastávky**, ne jen na konečné.
Spoj, který je na půlce trasy o 12 minut pozadu a na konečnou dojede včas, se tak započítá správně jako nedochvilný.

| Zastávka | A | B | C | D | E | F | G |
|---|---|---|---|---|---|---|---|
| Zpoždění při odjezdu | 0 | +6 min | +12 min | +8 min | +4 min | +2 min | +1 min |
| Odjezd | ✅ | ❌ | ❌ | ❌ | ❌ | ✅ | ✅ |

→ 3 ze 7 odjezdů včas, největší zpoždění na trase +12 min.

## Vstupní data (Golemio `vehiclepositions`) 🔍

| Význam | Předpokládaná cesta v `features[].properties` |
|---|---|
| ID spoje | `trip.gtfs.trip_id` |
| Začátek spoje | `trip.start_timestamp` |
| Linka | `trip.gtfs.route_id`, `trip.gtfs.route_short_name` |
| Typ dopravy | `trip.gtfs.route_type` |
| Směr | `trip.gtfs.trip_headsign` |
| Vozidlo | `trip.vehicle_registration_number` |
| Aktuální zpoždění [s] | `last_position.delay.actual` (může být `null`) |
| Zpoždění při odjezdu z poslední zastávky [s] | `last_position.delay.last_stop_departure` |
| Poslední zastávka (pořadí na trase) | `last_position.last_stop.sequence` |
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
| Nárůst zpoždění od posledního snapshotu ≤ uplynulý čas + 120 s | Zpoždění nemůže růst rychleji než čas; skok +60 min za 30 s je chyba dat |

Víc vozidel na jednom spoji (spřažené soupravy) → bere se nejnovější snapshot.

## Měření odjezdů

- Když se v platném snapshotu objeví zastávka s vyšším `sequence`, než jakou už spoj má, zaznamená se **jeden odjezd** se zpožděním `delay.last_stop_departure`.
- Každá zastávka spoje se počítá nejvýš jednou.
- Zastávky, které vozidlo projede mezi dvěma staženími, se nezměří (chybí, nezkreslují).

| Odjezd | Zpoždění při odjezdu | Dochvilný? |
|---|---|---|
| Předjetí | ≤ −60 s | ❌ (cestujícímu spoj ujel) |
| Včas | −59 až +179 s | ✅ |
| Mírné zpoždění | 180–299 s | ❌ |
| Výrazné zpoždění | 300–899 s | ❌ |
| Kritické zpoždění | ≥ 900 s | ❌ |

## Hodnoty spoje

| Hodnota | Definice | Použití |
|---|---|---|
| `last_delay` | Poslední platné zpoždění | Živý stav |
| `max_delay` | Max z `min(d[i], d[i-1], d[i-2])`, tj. musí vydržet 3 snapshoty | Součet, rekordman |
| `max_delay` (krátký spoj) | Má-li spoj < 3 platné snapshoty: minimum z dostupných | Žádný spoj s daty nemá `NULL` |
| `max_delay_at` | Kdy bylo maximum dosaženo | „+74 min v 16:42“ |
| `dep_total` / `dep_on_time` / `dep_early` / `dep_late` | Počty odjezdů podle tabulky výše | Index dochvilnosti |
| `dep_late_sum` | Σ zpoždění zpožděných odjezdů | Průměr zpožděného odjezdu |

Klouzavé okno posledních snapshotů žije jen v paměti serveru, ne v DB.

## Životní cyklus spoje

| Z | Událost | Do |
|---|---|---|
| — | první platný snapshot | `running` |
| `running` | `state_position = after_track` | `finished` |
| `running` | 15 min bez snapshotu | `finished` (timeout) |
| `running` | `state_position = canceled` | `canceled` |

Ostatní stavy (`off_track`, `mismatched`, `before_track`, …) spoj neukončí, jen se nepoužijí pro výpočet. 🔍
Spoj bez jediného platného snapshotu se nepočítá.

## Metriky (za provozní den)

| Metrika | Výpočet |
|---|---|
| Index dochvilnosti | Σ `dep_on_time` / Σ `dep_total` × 100 |
| Živý stav | Podíl `running` spojů s `last_delay` ≥ 180 |
| Součet zpoždění | Σ max(0, `max_delay`) přes všechny spoje |
| Průměr zpožděného odjezdu | Σ `dep_late_sum` / Σ `dep_late` |
| Předjetí | Σ `dep_early` |
| Zrušené spoje | Počet `canceled` |

## Kategorie (semafor)

| Kategorie | Pravidlo (PID GTFS `routes.txt`) |
|---|---|
| `metro` | `route_type = 1` (pravděpodobně ve feedu chybí → nezobrazovat) |
| `tram` | `route_type = 0` |
| `bus_city` | `route_type = 3`, `is_regional = 0` |
| `bus_regional` | `route_type = 3`, `is_regional = 1` |
| `train` | `route_type = 2` |
| `other` | Trolejbus (11), přívoz (4), lanovka (7) |

Noční linka (`is_night`) a náhradní doprava (`is_substitute_transport`) jsou příznaky, ne kategorie.

## Žebříčky

| Žebříček | Výpočet | Podmínka |
|---|---|---|
| Největší součet zpoždění | Σ `max_delay` za linku | — |
| Nejvyšší zpoždění na spoj | Σ `max_delay` / počet spojů | ≥ 20 `finished` |
| Nejhorší dochvilnost | Index dochvilnosti linky (z odjezdů) | ≥ 20 `finished` |
| Rekordman dne | Spoj s nejvyšším `max_delay` | — |

Zpoždění nad 2 h se zobrazí, ale pošle se upozornění správci ke kontrole.
