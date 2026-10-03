# ADR-0011: Žebříčky linek jen s ≥ 20 dokončenými spoji

- **Stav:** Přijato
- **Oblast:** Metodika
- **Datum:** 2026-10-02

## Kontext

Linka se dvěma spoji by v žebříčku průměrů vyhrála náhodou.

## Rozhodnutí

Žebříčky „nejvyšší zpoždění na spoj“ a „nejhorší dochvilnost“ zahrnují jen linky s alespoň `MIN_TRIPS` (výchozí 20) dokončenými spoji za den.

## Zvažované alternativy

| Alternativa | Proč ne |
|---|---|
| Bez limitu | Náhodné výsledky. |

## Důsledky

- Žebříček „součet zpoždění“ limit nemá.
