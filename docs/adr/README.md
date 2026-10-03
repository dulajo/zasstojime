# Rozhodnutí (ADR)

Každé rozhodnutí má vlastní soubor: kontext, rozhodnutí, alternativy, důsledky.

**Pravidla:** ADR se nemažou. Změna = nové ADR „nahrazuje ADR-XXXX“ a staré dostane stav „Nahrazeno“.

**Stavy:** ✅ Přijato · 🔍 K ověření · ⛔ Nahrazeno

## Data

| ADR | Rozhodnutí | Stav |
|---|---|---|
| [0001](0001-zdroj-golemio-vehiclepositions.md) | Zdroj real-time dat: Golemio vehiclepositions | ✅ |
| [0002](0002-kategorie-z-pid-gtfs.md) | Kategorie linek z PID GTFS | ✅ |
| [0045](0045-uzly.md) | Zastávky seskupovat podle stanice (uzlu) | ✅ |

## Metodika

| ADR | Rozhodnutí | Stav |
|---|---|---|
| [0003](0003-klic-spoje.md) | Spoj = provozní den + trip_id | ✅ |
| [0004](0004-provozni-den.md) | Provozní den = datum výjezdu spoje | ✅ |
| [0005](0005-potvrzene-maximum.md) | Maximum zpoždění platné po 3 snímcích | ⛔ Nahrazeno ADR-0039 |
| [0006](0006-index-z-konecne.md) | Index dochvilnosti ze zpoždění na konečné | ⛔ Nahrazeno ADR-0033 |
| [0007](0007-hranice-vcas.md) | Hranice „včas“ = zpoždění pod 180 s | ✅ |
| [0008](0008-predjeti-vcas.md) | Jízda napřed = včas | ⛔ Nahrazeno ADR-0034 |
| [0009](0009-zrusene-spoje.md) | Zrušené spoje mimo index, zobrazují se zvlášť | ✅ |
| [0010](0010-suspect-2h.md) | Rekordman jen bez příznaku podezřelé (> 2 h) | ⛔ Nahrazeno ADR-0035 |
| [0011](0011-min-spoju-zebricky.md) | Žebříčky linek jen s ≥ 20 dokončenými spoji | ✅ |
| [0013](0013-metro.md) | Metro zobrazit, jen pokud jsou jeho zpoždění reálná | 🔍 |
| [0033](0033-dochvilnost-na-odjezdech.md) | Dochvilnost se měří na odjezdech ze zastávek | ✅ nahrazuje ADR-0006 |
| [0034](0034-predjeti.md) | Předjetí (odjezd o ≥ 60 s dřív) = nedochvilnost | ✅ nahrazuje ADR-0008 |
| [0035](0035-nerealne-skoky.md) | Chyby dat podle nereálného skoku, ne podle pevné hranice | ✅ nahrazuje ADR-0010 |
| [0036](0036-kratke-spoje.md) | Krátké spoje: maximum z dostupných snímků | ⛔ Nahrazeno ADR-0039 |
| [0039](0039-zpozdeni-z-odjezdu.md) | Všechna zpoždění spoje se berou z odjezdů, ne z delay.actual | ✅ nahrazuje ADR-0005, ADR-0036, ADR-0037 |
| [0040](0040-preskok-zpet.md) | Snímek s klesající sekvencí zastávky je neplatný | ✅ |
| [0041](0041-hodnota-odjezdu.md) | Zpoždění odjezdu = poslední hodnota před posunem na další zastávku | ✅ |
| [0042](0042-znovuotevreni.md) | Dojetý spoj se může znovu otevřít | ✅ |
| [0046](0046-typ-dne.md) | Typ dne s českými svátky | ✅ |
| [0047](0047-moje-trasa.md) | Moje trasa: přímé spoje, hledání podle plánovaného času | ✅ |

## Komunikace

| ADR | Rozhodnutí | Stav |
|---|---|---|
| [0012](0012-formulace-soucet.md) | Na webu „součet zpoždění spojů“, ne „čas cestujících“ | ✅ |

## Architektura

| ADR | Rozhodnutí | Stav |
|---|---|---|
| [0014](0014-jedna-aplikace.md) | Server = jedna aplikace (sběr + API) | ✅ |
| [0015](0015-trips-daily.md) | Hlavní tabulka na úrovni spoje: trips_daily | ✅ |
| [0016](0016-postgresql.md) | Čistý PostgreSQL 16 | ✅ |
| [0017](0017-predpocitane-odpovedi.md) | API vrací předpočítané odpovědi, CDN cache 30 s | ✅ |
| [0018](0018-surove-snimky.md) | Surové snímky jako pojistka, retence 7 dní | ✅ |
| [0037](0037-okno-v-pameti.md) | Klouzavé okno snímků jen v paměti | ⛔ Nahrazeno ADR-0039 |
| [0038](0038-zive-v-pameti.md) | Živá čísla v paměti, do DB jen změny | ✅ |
| [0044](0044-stop-events.md) | Ukládat každý průjezd zastávkou | ✅ |

## Technologie

| ADR | Rozhodnutí | Stav |
|---|---|---|
| [0019](0019-quarkus.md) | Server: Kotlin + Quarkus | ✅ |
| [0020](0020-jooq.md) | Přístup k DB: jOOQ | ✅ |
| [0021](0021-flyway.md) | Migrace: Flyway | ✅ |
| [0022](0022-testy-skutecny-postgres.md) | Testy proti skutečnému PostgreSQL | ✅ |
| [0023](0023-nextjs.md) | Klient: Next.js + TypeScript | ✅ |
| [0024](0024-typy-z-openapi.md) | TS typy generované z OpenAPI | ✅ |

## Provoz

| ADR | Rozhodnutí | Stav |
|---|---|---|
| [0025](0025-homelab.md) | Server v homelabu | ✅ |
| [0026](0026-cloudflare-tunnel.md) | Veřejný přístup jen přes Cloudflare Tunnel | ✅ |
| [0027](0027-frontend-cloudflare.md) | Frontend hostovaný na Cloudflare | ✅ |
| [0028](0028-domena.md) | Nová .cz doména, DNS na Cloudflare | ✅ |
| [0029](0029-navstevnost.md) | Návštěvnost: Cloudflare Web Analytics | ✅ |
| [0030](0030-monitoring.md) | Monitoring: Micrometer → Prometheus → Grafana | ✅ |
| [0031](0031-zalohy.md) | Zálohy: denní pg_dump mimo homelab | ✅ |
| [0032](0032-licence.md) | Uvádět zdroj, nepoužívat loga PID/DPP | ✅ |
| [0043](0043-sber-az-s-pohonem.md) | Sběr se spustí až s hotovým pohonem | ✅ |
| [0049](0049-faze-1-po-castech.md) | Fáze 1 se nasazuje po částech | ✅ |

## Produkt

| ADR | Rozhodnutí | Stav |
|---|---|---|
| [0048](0048-bez-uctu.md) | Bez uživatelských účtů, trasa v URL | ✅ |
