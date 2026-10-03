# ADR-0053: Chybějící odjezd v „Moje trasa“ nahradit příjezdem

- **Stav:** Přijato
- **Oblast:** Metodika
- **Datum:** 2026-10-03

## Kontext

U 15,9 % zastávek je vozidlo zachyceno jen stojící a zpoždění odjezdu chybí. U 98,7 % z nich je známé zpoždění příjezdu. Příjezd a odjezd padnou do stejného pásma v 94,8 % případů (odjezd typicky o 24 s později).

## Rozhodnutí

Index dochvilnosti a žebříčky počítají **jen změřené odjezdy**. „Moje trasa“ použije v nástupní stanici zpoždění příjezdu, pokud odjezd chybí, a den označí jako „odhad“.

## Zvažované alternativy

| Alternativa | Proč ne |
|---|---|
| Nepoužívat příjezd | Pro „Moje trasa“ by chybělo ~16 % dní. |
| Příjezd i do indexu | ~5 % špatně zařazených odjezdů, hlavně předjetí. |

## Důsledky

- `stop_events` už příjezd ukládá; žádná změna schématu.
