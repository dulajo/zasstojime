# ADR-0033: Dochvilnost se měří na odjezdech ze zastávek

- **Stav:** Přijato · nahrazuje ADR-0006
- **Oblast:** Metodika
- **Datum:** 2026-10-02

## Kontext

Spoj může být v půlce trasy o 12 min pozadu a na konečnou dojet včas díky vyrovnávací době. Cestující na trase zpoždění zažili.

## Rozhodnutí

Každý odjezd ze zastávky je jedno měření. Index dochvilnosti = odjezdy v toleranci / všechny změřené odjezdy.

## Zvažované alternativy

| Alternativa | Proč ne |
|---|---|
| Zpoždění na konečné (ADR-0006) | Skryje zpoždění na trase. |
| Průměr ze snímků | Pomalé spoje mají víc snímků a zkreslí průměr. |

## Důsledky

- Záznam 2 h: 42 689 změřených odjezdů. 1,3 % zastávek vozidlo projede mezi snímky; u dalších 15,9 % je vozidlo zachyceno jen stojící a zpoždění odjezdu chybí (viz ADR-0053).
- Index je srovnatelný s praxí dopravců.

## Podklady

- [metodika.md – Měření odjezdů](../metodika.md#měření-odjezdů)
- [faze0.md](../faze0.md)
