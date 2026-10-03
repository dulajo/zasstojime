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

## 2h záznam (2. 10. 2026, 20:17–22:15, snímek à 30 s)

228 snímků, 5 235 spojů, 47 MB gzip.

| Otázka | Zjištění | Dopad |
|---|---|---|
| Funguje měření odjezdů? | 42 689 změřených odjezdů. 1,3 % zastávek vozidlo projede mezi snímky; u dalších 15,9 % je zachyceno jen stojící a zpoždění odjezdu chybí (u 98,7 % z nich je známý příjezd) | ✅ ADR-0033, ADR-0053 |
| Ustálí se hodnota odjezdu? | Na zastávce se mění (9 122 zastávek), po odjezdu stabilní (výjimek 220) | Brát poslední hodnotu před posunem (#41) |
| Jak spoje končí? | Z 3 609 jedoucích: 2 499 → `after_track`, 474 zmizelo bez něj (13 %) | Timeout nutný |
| Je `after_track` definitivní? | 41 spojů se pak vrátilo na trasu | Znovuotevření (#42) |
| Zrušené spoje | 6, z toho 1 zrušen až za jízdy | — |
| Skoky v `actual` | Ze 137 tis. dvojic snímků: 99,9 % roste ≤ 60 s nad uplynulý čas; 42 skoků > 120 s, **všechny s přeskokem zpět na trase** | Tolerance 120 s sedí; přeskok zpět = neplatný (#40) |
| `actual` vs. odjezdy | Max z `actual` (i se 3 snapshoty) je u 19 spojů o > 10 min vyšší než max z odjezdů; falešní „rekordmani“ 78, 35 a 34 min | Vše počítat z odjezdů (#39) |
| Metro | 7 263 vzorků, max 66 s, odjezdy 100 % včas | Stále nejasné (ADR-0013) |
| Dvě vozidla na spoji | 5 spojů, vozidla na různých místech trasy (sekvence 9 a 1) | Chybné přiřazení, ne soupravy (ADR-0052) |
| Dlouhé přeskoky zpět | 17 běhů ≥ 10 snímků; vozidlo jede trasu pozpátku (7 → 6 → … → 2) | Vozidlo opačného směru, ignorovat (ADR-0040) |
| Nereálný pokles zpoždění | 8 z 19 346 poklesů > uplynulý čas + 120 s | ADR-0051 |
| Příjezd vs. odjezd | Stejné pásmo v 94,8 %, odjezd typicky o 24 s později | ADR-0053 |
| Výjezdy ≥ 24:00 v GTFS | 1 422 z 87 651 spojů | ADR-0050 |

### Index dochvilnosti z odjezdů (večer, mimo špičku)

(První průchod analýzy, který bral i první zachycenou hodnotu odjezdu. Přesná pravidla ADR-0041 dají 42 689 odjezdů; poměry se liší v desetinách procenta.)

| Typ | Odjezdů | Včas | Předjetí |
|---|---|---|---|
| Celkem | 46 430 | **87,4 %** | 0,3 % |
| Tramvaj | 7 694 | 92,2 % | 0,0 % |
| Autobus | 33 121 | 86,5 % | 0,3 % |
| Trolejbus | 755 | 84,8 % | 0,3 % |
| Vlak | 2 945 | 77,2 % | 1,2 % |
| Metro | 1 901 | 100 % | 0,0 % |

## Zbývá

- Záznam v ranní špičce (kalibrace na horších podmínkách).
- Metro při známé mimořádnosti (#13).
