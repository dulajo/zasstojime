# ADR-0013: Metro zobrazit, jen pokud jsou jeho zpoždění reálná

- **Stav:** K ověření
- **Oblast:** Metodika
- **Datum:** 2026-10-02

## Kontext

Metro ve feedu je, ale za 2 h mělo max. 66 s zpoždění a 100 % odjezdů včas. Může jít o dopočet z jízdního řádu.

## Rozhodnutí

Metro se sbírá stejně jako ostatní. Na webu se zobrazí až po ověření při známé mimořádnosti; do té doby s poznámkou nebo skryté.

## Zvažované alternativy

| Alternativa | Proč ne |
|---|---|
| Natvrdo zadané „obvykle 99 %“ | Nepodložené daty. |

## Důsledky

- Otevřená otázka č. 1.

## Podklady

- [faze0.md](../faze0.md)
