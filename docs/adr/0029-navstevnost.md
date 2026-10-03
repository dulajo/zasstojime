# ADR-0029: Návštěvnost: Cloudflare Web Analytics

- **Stav:** Přijato
- **Oblast:** Provoz
- **Datum:** 2026-10-02

## Kontext

Chceme vidět návštěvnost bez cookie lišty a bez zátěže homelabu.

## Rozhodnutí

Cloudflare Web Analytics.

## Zvažované alternativy

| Alternativa | Proč ne |
|---|---|
| Umami (self-hosted) | Každá návštěva by šla do homelabu. |
| Grafana | Není na webovou analytiku. |
