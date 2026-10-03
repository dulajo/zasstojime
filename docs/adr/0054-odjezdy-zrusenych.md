# ADR-0054: Odjezdy změřené před zrušením spoje se počítají

- **Stav:** Přijato
- **Oblast:** Metodika
- **Datum:** 2026-10-03

## Kontext

Spoj může být zrušen až za jízdy (záznam: 1 ze 6). Review navrhlo jeho odjezdy z indexu vyřadit.

## Rozhodnutí

Odjezdy, které spoj reálně uskutečnil před zrušením, se do indexu počítají. Zrušení se navíc počítá do „zrušených spojů“.

## Zvažované alternativy

| Alternativa | Proč ne |
|---|---|
| Vyřadit odjezdy zrušených spojů (návrh review) | Cestující ty odjezdy zažili; zahodit je by zkreslilo index. |
