# Rozhodnutí (ADR log)

Jeden řádek = jedno rozhodnutí. Detaily výpočtů jsou v [metodika.md](metodika.md), technické detaily v [technicke.md](technicke.md).

**Pravidla:** řádky se nemažou. Změna rozhodnutí = nový řádek se stavem „Nahrazuje #N“ a u starého řádku se stav změní na „Nahrazeno #M“.

**Stavy:** ✅ Přijato · 🔍 K ověření (Fáze 0) · 🔁 Přehodnotit později

## Data a metodika

| # | Rozhodnutí | Proč | Zamítnuto | Stav |
|---|---|---|---|---|
| 1 | Real-time zdroj: Golemio `/v2/vehiclepositions?includeNotTracking=true` (gzip), polling 30 s | Jediný zdroj se zpožděním při odjezdu, začátkem spoje a všemi stavy (dojeté, zrušené); ~270 kB/snímek ≈ 0,8 GB/den | `/v2/public/vehiclepositions` (29 kB, ale chybí odjezdy a začátek spoje); GTFS-RT (větší, bez PID stavů) | ✅ |
| 2 | Kategorie linek z PID GTFS `routes.txt`: `route_type` + `is_regional`, `is_night`, `is_substitute_transport`; import denně | Ověřeno 2. 10. 2026: sloupce existují; regionální autobusy mají čísla 300–999, městské i 5xx a 9xx, trolejbus 275 by podle čísla vyšel jako autobus | Kategorie podle čísla linky | ✅ |
| 3 | Spoj = `(service_date, trip_id)` | `trip_id` se opakuje ve všech dnech platnosti jízdního řádu | Samotné `trip_id` | ✅ |
| 4 | Provozní den = datum výjezdu spoje (Europe/Prague) | Noční spoj nepřeskočí po půlnoci do dalšího dne | Datum snapshotu | ✅ |
| 5 | Maximum zpoždění platí až po 3 platných snapshotech po sobě | Odfiltruje GPS skoky a chybné párování | Prosté maximum | Nahrazeno #39 |
| 6 | Index dochvilnosti z `final_delay` dokončených spojů | Odpovídá příjezdu na konečnou, je stabilní | Z maxima (moc přísné), z aktuálního stavu (kolísá) | Nahrazeno #33 |
| 7 | „Včas“ = zpoždění < 180 s | Tolerance PID | — | ✅ |
| 8 | Jízda napřed = včas, eviduje se zvlášť | Jednoduchost pro MVP | Penalizovat jako zpoždění | Nahrazeno #34 |
| 9 | Zrušené spoje mimo index, zobrazit zvlášť | Nemají zpoždění, ale jsou podstatné | Započítat jako zpožděné | ✅ |
| 10 | Rekordman jen bez příznaku `suspect` (> 2 h) | Jedna chybná hodnota = veřejná ostuda | — | Nahrazeno #35 |
| 11 | Žebříčky linek jen s ≥ 20 dokončenými spoji | Linka se dvěma spoji by vyhrála náhodou | Bez limitu | ✅ |
| 12 | Na webu „součet zpoždění spojů“, ne „čas cestujících“ | Obsazenost vozidel neznáme | — | ✅ |
| 13 | Metro zobrazit jen pokud jsou jeho zpoždění reálná | Metro ve feedu je, ale za 2 h max. 66 s a 100 % odjezdů včas; může jít o dopočet z jízdního řádu; ověřit při známé mimořádnosti | Natvrdo zadané číslo | 🔍 |
| 33 | Dochvilnost se měří na každém odjezdu ze zastávky (nahrazuje #6) | Cestující, který jel jen do půlky trasy, zpoždění zažil, i když spoj na konečnou dojel včas; ověřeno: změřeno 46 tis. odjezdů za 2 h, nezachyceno jen 1,3 % zastávek | Zpoždění na konečné (#6); průměr snapshotů (pomalé spoje mají víc snapshotů) | ✅ |
| 34 | Odjezd o ≥ 60 s dřív = předjetí, počítá se jako nedochvilnost (nahrazuje #8) | Cestujícímu spoj ujel; tak to bere i PID | Předjetí = včas (#8) | ✅ |
| 35 | Chyby dat se poznají podle nereálného skoku: nárůst zpoždění ≤ uplynulý čas + 120 s; > 2 h jen upozornění správci (nahrazuje #10) | Reálné zpoždění 140 min při kalamitě se zobrazí, chyba dat ne | Pevná hranice 2 h (#10) | ✅ |
| 36 | Spoj s < 3 platnými snapshoty: `max_delay` = minimum z dostupných | Krátké spoje nesmí mít `NULL` | Vynechat krátké spoje | Nahrazeno #39 |
| 39 | Všechna zpoždění spoje (max, živý stav, rekordman) se berou z odjezdů ze zastávek, ne z `delay.actual` (nahrazuje #5, #36) | Záznam: `actual` ulétl stabilně na desítky minut (bus 410: 78 min vs. 9 min na odjezdech); pravidlo 3 snapshotů to nechytí | Maximum z `actual` se 3 snapshoty (#5) | ✅ |
| 40 | Snapshot, kde `last_stop.sequence` klesne, je neplatný | Všechny velké skoky v záznamu byly přeskoky zpět na trase (chybné spárování) | — | ✅ |
| 41 | Zpoždění odjezdu = poslední hodnota pro zastávku před posunem na další | Na zastávce se hodnota průběžně mění, ustálí se až po odjezdu | První zachycená hodnota | ✅ |
| 42 | `after_track` spoj ukončí, ale pokud se do 15 min objeví znovu jedoucí, spoj se znovu otevře | Záznam: 41 z 2 499 spojů se po `after_track` vrátilo na trasu | Ukončit natvrdo | ✅ |
| 43 | Sběr dat se spustí až s hotovým pohonem, žádný dočasný archivátor předem | Jednodušší; historie začne dnem spuštění | Archivovat surové snímky už teď a později je přehrát | ✅ |

## Architektura

| # | Rozhodnutí | Proč | Zamítnuto | Stav |
|---|---|---|---|---|
| 14 | Server = jedna aplikace (worker + API), uvnitř oddělené moduly | Méně věcí k provozu | Oddělené služby | ✅ |
| 15 | Hlavní tabulka `trips_daily` (1 řádek = spoj × den) | Raw snapshoty = miliony zbytečných řádků denně | Ukládat každý snapshot | ✅ |
| 16 | Čistý PostgreSQL 16 | Data jsou malá, cache řeší CDN | TimescaleDB, Redis | ✅ |
| 17 | API vrací předpočítané odpovědi, CDN cache 30 s | Návštěvnost nezatíží homelab | Výpočet při každém požadavku | ✅ |
| 18 | Surové payloady gzip, retence 7 dní | Ladění, přepočet po opravě chyby; ~0,8 GB/den, tj. ~6 GB celkem | Neukládat | ✅ |
| 37 | Klouzavé okno snapshotů jen v paměti serveru, ne v DB | Změna délky okna nevyžaduje migraci; po restartu ztratíme max. 2 snapshoty | Sloupce `prev_delay_*` v DB | Nahrazeno #39 (okno už není potřeba) |
| 38 | Živá čísla počítá server v paměti; do DB jen změněné spoje, denní tabulky každých 5 min a na konci dne | Méně zápisů, jednodušší | Přepočet DB agregátů v každém cyklu | ✅ |

## Technologie

| # | Rozhodnutí | Proč | Zamítnuto | Stav |
|---|---|---|---|---|
| 19 | Server: Kotlin + **Quarkus** | Naučit se ho; Dev Services, dev mode | Micronaut (známý), Spring Boot | ✅ |
| 20 | Přístup k DB: **jOOQ** | Agregace jsou SQL, typově bezpečně | Hibernate/Panache, Exposed | ✅ |
| 21 | Migrace: **Flyway** | Naučit se ho; prosté verzované SQL | Liquibase (známý) | ✅ |
| 22 | Testy proti skutečnému Postgresu (Dev Services / Testcontainers) | Mocky by nechytily chyby v SQL | H2, mocky | ✅ |
| 23 | Klient: Next.js + TypeScript (strict) + Tailwind + TanStack Query | Současný standard, SSR, OG obrázky | Kotlin/JS, Compose for Web | ✅ |
| 24 | TS typy generované z OpenAPI serveru | Změna API rozbije build frontendu | Ručně psané typy | ✅ |

## Provoz

| # | Rozhodnutí | Proč | Zamítnuto | Stav |
|---|---|---|---|---|
| 25 | Server v homelabu: izolovaná VM/LXC + VLAN, Docker Compose | Vlastní HW; přenositelné na VPS | VPS | ✅ |
| 26 | Veřejně jen přes Cloudflare Tunnel; NPM + WireGuard zůstávají pro správu | Žádný otevřený port, skrytá IP, CDN | NPM veřejně, port forwarding | ✅ |
| 27 | Frontend hostovaný na Cloudflare | Zdarma i komerčně; virální provoz mimo homelab | Vercel (free tarif zakazuje komerci) | ✅ |
| 28 | Nová `.cz` doména u českého registrátora, DNS na Cloudflare | Cloudflare `.cz` neprodává; tunel vyžaduje DNS na Cloudflare | Subdoména `dulove.cz` (jen pro vývoj) | ✅ |
| 29 | Návštěvnost: Cloudflare Web Analytics | Bez cookies, bez zátěže homelabu | Umami, Grafana | ✅ |
| 30 | Monitoring: Micrometer → Prometheus → Grafana (jen přes VPN) | Už v homelabu je | — | ✅ |
| 31 | Zálohy: denní `pg_dump` mimo homelab | Výpadek disku doma | — | ✅ |
| 32 | Uvádět zdroj (Golemio, CC BY 4.0), žádná loga PID/DPP | Licence; nevzbuzovat dojem oficiálního webu | — | ✅ |
