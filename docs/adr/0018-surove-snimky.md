# ADR-0018: Surové snímky jako pojistka, retence 7 dní

- **Stav:** Přijato
- **Oblast:** Architektura
- **Datum:** 2026-10-02

## Kontext

Při chybě v logice chceme mít možnost dny přepočítat.

## Rozhodnutí

Server volitelně ukládá surové snímky (gzip); v noci je zabalí po dnech (xz, ~130 MB/den). Drží se 7 dní.

## Zvažované alternativy

| Alternativa | Proč ne |
|---|---|
| Neukládat | Chybu v logice by nešlo opravit zpětně. |

## Důsledky

- Záznam 2 h: 47 MB po souborech, 13,5 MB zabaleno po dávce.
