# ADR-0041: Zpoždění odjezdu = poslední hodnota před posunem na další zastávku

- **Stav:** Přijato
- **Oblast:** Metodika
- **Datum:** 2026-10-02

## Kontext

Dokud vozidlo stojí na zastávce, `last_stop_departure` se mění nebo je `null`; po odjezdu je stabilní.

## Rozhodnutí

Pro každou zastávku se drží poslední nenulová hodnota; odjezd se zaznamená, když se objeví vyšší sekvence.

## Zvažované alternativy

| Alternativa | Proč ne |
|---|---|
| První zachycená hodnota | Na zastávce se ještě mění. |

## Důsledky

- Záznam: 9 122 zastávek s měnící se hodnotou, po odjezdu 220 výjimek.
