# ADR-0042: Dojetý spoj se může znovu otevřít

- **Stav:** Přijato
- **Oblast:** Metodika
- **Datum:** 2026-10-02

## Kontext

Záznam: 41 z 2 499 spojů se po `after_track` vrátilo na trasu; 13 % spojů zmizí bez `after_track`.

## Rozhodnutí

`after_track` / `after_track_delayed` spoj ukončí; pokud do 15 min přijde platný jedoucí snímek, spoj se znovu otevře. Spoj bez snímku 15 min se ukončí timeoutem.

## Zvažované alternativy

| Alternativa | Proč ne |
|---|---|
| Ukončit natvrdo | Ztratili bychom zbytek jízdy. |
