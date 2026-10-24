# ADR-0058: Bez „Moje trasa“, web jen o dochvilnosti sítě

- **Stav:** Přijato
- **Oblast:** Produkt
- **Datum:** 2026-10-24
- **Nahrazuje:** ADR-0047, ADR-0053

## Kontext

Web měl dvě různé věci: přehled dochvilnosti celé sítě a „Moje trasa“, vyhledávání vlastního spoje
s jeho historií. Druhá část je jiný produkt (osobní nástroj, formulář linka–směr–zastávky–čas),
rozmělňuje hlavní sdělení a přidává pět endpointů, které je potřeba udržovat a ladit nad 13 měsíci dat.

## Rozhodnutí

Odstranit „Moje trasa“: stránku `/moje-trasa/` a endpointy `/api/my-route`, `/api/lines…`, `/api/trip`
a `/api/calendar`. Web odpovídá na jednu otázku: jak dochvilná je PID dnes a proč.

Sběr se nemění. `stop_events` i noční souhrn `stop_route_hourly` zůstávají, protože z nich vychází
hodinový vývoj a později detail linky (Fáze 3).

## Zvažované alternativy

| Alternativa | Proč ne |
|---|---|
| Nechat a schovat z navigace | Mrtvý kód a API, které nikdo nepoužívá, ale musí se udržovat. |
| Oddělit do vlastní aplikace | Možné později; data na to v `stop_events` zůstávají. |

## Důsledky

- Méně API a menší web; metodika popisuje jen čísla, která web ukazuje.
- Návrat je možný z historie gitu (commit před tímto ADR) nad stejnými daty.
