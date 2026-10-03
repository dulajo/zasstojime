# ADR-0017: API vrací předpočítané odpovědi, CDN cache 30 s

- **Stav:** Přijato
- **Oblast:** Architektura
- **Datum:** 2026-10-02

## Kontext

Virální návštěvnost nesmí zatížit homelab.

## Rozhodnutí

Odpovědi dashboardu se počítají po každém cyklu sběru; API vrací hotový JSON s `Cache-Control: public, s-maxage=30, stale-while-revalidate=60`.

## Zvažované alternativy

| Alternativa | Proč ne |
|---|---|
| Výpočet při každém požadavku | Zátěž roste s návštěvností. |

## Důsledky

- „Moje trasa“ se počítá na dotaz, ale je cacheovaná podle URL (výsledek se během dne mění málo).
