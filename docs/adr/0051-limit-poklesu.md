# ADR-0051: Symetrický limit: zpoždění nesmí ani nereálně klesnout

- **Stav:** Přijato
- **Oblast:** Metodika
- **Datum:** 2026-10-03

## Kontext

ADR-0035 hlídá jen nárůst. Chyba dat se může projevit i skokovým poklesem (např. z 60 min na 0).

## Rozhodnutí

Odjezd se zahodí i tehdy, když jeho zpoždění oproti předchozímu odjezdu klesne o víc než uplynulý čas + `MAX_DELAY_JUMP_S`.

## Zvažované alternativy

| Alternativa | Proč ne |
|---|---|
| Hlídat jen nárůst | Pokles by tvářil zpožděný spoj jako včasný. |

## Důsledky

- Záznam: ze 19 346 poklesů by pravidlo zahodilo 8.
