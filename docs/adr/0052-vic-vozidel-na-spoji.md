# ADR-0052: Více vozidel na jednom spoji: vybrat to, které navazuje na průběh

- **Stav:** Přijato
- **Oblast:** Metodika
- **Datum:** 2026-10-03

## Kontext

Záznam: 5 spojů hlášených dvěma vozidly, ale na různých místech trasy (sekvence 9 a 1, 10 a 22). Nejsou to spřažené soupravy, ale chybné přiřazení. Pravidlo „nejnovější snímek“ by mezi nimi přeskakovalo.

## Rozhodnutí

Z vozidel hlášených pro jeden spoj se vezme to s nejnižší sekvencí, která je ≥ dosavadní maximální sekvenci spoje. U nového spoje to s nejnižší sekvencí. Ostatní se ignorují a počítají do metriky.

## Zvažované alternativy

| Alternativa | Proč ne |
|---|---|
| Nejnovější snímek | Přeskakuje mezi vozidly. |
| Uzamknout spoj na první vozidlo (návrh review) | U metra a vlaků chybí číslo vozidla; když první vozidlo přestane hlásit, ztratíme spoj. |
