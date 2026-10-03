# ADR-0035: Chyby dat podle nereálného skoku, ne podle pevné hranice

- **Stav:** Přijato · nahrazuje ADR-0010
- **Oblast:** Metodika
- **Datum:** 2026-10-02

## Kontext

Pevná hranice 2 h by při kalamitě vyřadila reálné zpoždění 140 min.

## Rozhodnutí

Zpoždění odjezdu nesmí oproti předchozímu odjezdu narůst o víc než uplynulý čas + 120 s; takový odjezd se zahodí. Zpoždění nad 2 h se zobrazí a pošle se upozornění správci.

## Zvažované alternativy

| Alternativa | Proč ne |
|---|---|
| Pevná hranice 2 h (ADR-0010) | Vyřadí i reálné kalamity. |

## Důsledky

- Záznam: 99,9 % nárůstů je ≤ 60 s nad uplynulý čas.
