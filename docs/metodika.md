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

## Vstupní data (Golemio `/v2/vehiclepositions?includeNotTracking=true`)

Ověřeno na reálných datech, viz [faze0.md](faze0.md).

| Význam | Cesta v `features[].properties` |
|---|---|
| ID spoje | `trip.gtfs.trip_id` |
| Začátek spoje | `trip.start_timestamp` |
| Linka | `trip.gtfs.route_id`, `trip.gtfs.route_short_name` |
| Typ dopravy | `trip.gtfs.route_type` |
| Směr | `trip.gtfs.trip_headsign` |
| Vozidlo | `trip.vehicle_registration_number` (u metra a vlaků `null`) |
| Aktuální zpoždění [s] | `last_position.delay.actual` (jen informativně, umí ulétnout) |
| Zpoždění při odjezdu z poslední zastávky [s] | `last_position.delay.last_stop_departure` |
| Poslední zastávka | `last_position.last_stop.sequence`, `last_position.last_stop.id` |
| Zrušený spoj | `last_position.is_canceled` |
| Čas polohy | `last_position.origin_timestamp` |
| Stav vozidla | `last_position.state_position` |

## Platný snapshot

Snapshot vstupuje do výpočtů, jen když platí **vše**:

| Podmínka | Důvod |
|---|---|
| `state_position` ∈ {`on_track`, `at_stop`} | Mimo trasu nebo před jízdou je zpoždění nespolehlivé |
| `last_stop.sequence` neklesne pod dosud nejvyšší | Přeskok zpět na trase = chybné spárování |
| `origin_timestamp` je mladší než 5 min | Vozidlo se reálně hlásí |
| `origin_timestamp` je novější než poslední zpracovaný | Deduplikace |
| Nárůst zpoždění odjezdu oproti předchozímu odjezdu ≤ uplynulý čas + 120 s | Zpoždění nemůže růst rychleji než čas |

Víc vozidel na jednom spoji (spřažené soupravy) → bere se nejnovější snapshot.

## Měření odjezdů

- Pro každou zastávku (`sequence`) se průběžně drží poslední nenulová hodnota `delay.last_stop_departure`.
- Když se objeví zastávka s vyšším `sequence`, předchozí zastávka se uzavře a zaznamená se **jeden odjezd** s poslední hodnotou.
- Dokud vozidlo na zastávce stojí, hodnota se mění (nebo je `null`). Ustálí se až po odjezdu.
- Každá zastávka spoje se počítá nejvýš jednou. Zastávky bez hodnoty a projeté mezi dvěma staženími se nezměří (chybí, nezkreslují).

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
| `last_delay` | Zpoždění posledního změřeného odjezdu | Živý stav |
| `max_delay` | Největší zpoždění odjezdu na trase | Součet, rekordman |
| `max_delay_at`, `max_delay_stop` | Kdy a z které zastávky | „+74 min v 16:42 z Florence“ |
| `dep_total` / `dep_on_time` / `dep_early` / `dep_late` | Počty odjezdů podle tabulky výše | Index dochvilnosti |
| `dep_late_sum` | Σ zpoždění zpožděných odjezdů | Průměr zpožděného odjezdu |

`delay.actual` se do výpočtů nepoužívá. V záznamu ulétl stabilně o desítky minut (bus 410: 78 min proti 9 min na odjezdech).

## Životní cyklus spoje

| Z | Událost | Do |
|---|---|---|
| — | první platný snapshot | `running` |
| `running` | `state_position` ∈ {`after_track`, `after_track_delayed`} | `finished` |
| `finished` | do 15 min znovu `on_track` / `at_stop` | `running` (znovu otevřen) |
| `running` | 15 min bez snapshotu | `finished` (timeout) |
| libovolný | `state_position = canceled` nebo `is_canceled = true` | `canceled` |

Stavy `before_track`, `before_track_delayed` a `off_track` spoj neukončí, jen se nepoužijí pro výpočet.
Zpoždění spoje, který vyjede pozdě z výchozí zastávky, se zachytí prvním odjezdem.
Spoj bez jediného změřeného odjezdu se nepočítá.

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
| `metro` | `route_type = 1` (ve feedu je, reálnost zpoždění 🔍) |
| `tram` | `route_type = 0` |
| `bus_city` | `route_type = 3`, `is_regional = 0` |
| `bus_regional` | `route_type = 3`, `is_regional = 1` |
| `train` | `route_type = 2` |
| `other` | Trolejbus (11), přívoz (4), lanovka (7) |

Noční linka (`is_night`) a náhradní doprava (`is_substitute_transport`) jsou příznaky, ne kategorie.

## Typ dne

| Typ | Dny |
|---|---|
| `workday` | Po–Pá, kromě státních svátků |
| `saturday` | Sobota, kromě státních svátků |
| `sunday_holiday` | Neděle a státní svátky |

## Moje trasa

Uživatel zvolí **linku, stanici nástupu, stanici výstupu, plánovaný čas odjezdu a typ dne**. Spoj se v každém dni dohledá podle linky, uzlu nástupu a plánovaného času, ne podle `trip_id` (#47). Jen přímé spoje.

| Metrika | Výpočet (za zvolené období) |
|---|---|
| Měřeno | Počet dní, kdy byl spoj změřen v nástupní i výstupní stanici |
| Včas na nástupu | Počet dní s odjezdem v toleranci (−59 až +179 s) / měřeno |
| Průměrné čekání navíc | Průměr max(0, zpoždění odjezdu v A) |
| Zpoždění v cíli | Průměr a maximum zpoždění příjezdu do B (když chybí, zpoždění odjezdu z B) |
| Jízda navíc | Průměr (zpoždění v B − zpoždění v A) |
| Celkem nasbíráno | Σ max(0, zpoždění v B) přes měřené dny |

„Celkem nasbíráno“ se na webu uvádí jako „kdybys jel tímto spojem každý (pracovní) den“. Nevíme, kdy uživatel skutečně jel.

## Žebříčky

| Žebříček | Výpočet | Podmínka |
|---|---|---|
| Největší součet zpoždění | Σ `max_delay` za linku | — |
| Nejvyšší zpoždění na spoj | Σ `max_delay` / počet spojů | ≥ 20 `finished` |
| Nejhorší dochvilnost | Index dochvilnosti linky (z odjezdů) | ≥ 20 `finished` |
| Rekordman dne | Spoj s nejvyšším `max_delay` | — |

Zpoždění nad 2 h se zobrazí, ale pošle se upozornění správci ke kontrole.
