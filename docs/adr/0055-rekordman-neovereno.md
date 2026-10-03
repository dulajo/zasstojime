# ADR-0055: Rekordman nad 2 h má příznak „neověřeno“

- **Stav:** Přijato
- **Oblast:** Metodika
- **Datum:** 2026-10-03

## Kontext

ADR-0035 rekordmana nad 2 h zobrazí a pošle upozornění. Do kontroly by se ale veřejně ukazovala případná chyba dat.

## Rozhodnutí

Spoj s `max_delay` > `ALERT_DELAY_S` dostane `flagged = true`. API ho vrací s příznakem a web u něj zobrazí štítek „neověřeno“. Správce může příznak zrušit (`verified = true`) nebo spoj vyřadit z rekordmana (`excluded = true`) přímo v DB.

## Zvažované alternativy

| Alternativa | Proč ne |
|---|---|
| Skrýt do ověření | Při kalamitě by web ukazoval nesprávného rekordmana. |
| Admin rozhraní | Zbytečné pro jednotky případů ročně. |
