# ADR-0030: Monitoring: Micrometer → Prometheus → Grafana

- **Stav:** Přijato
- **Oblast:** Provoz
- **Datum:** 2026-10-02

## Kontext

Je potřeba hlídat, že sběr běží.

## Rozhodnutí

Server exportuje metriky (Micrometer/Prometheus). Grafana v homelabu jen přes VPN. Alerty posílá Grafana Alerting (kontaktní bod Telegram, záloha e-mail); server sám nic neposílá.

## Zvažované alternativy

| Alternativa | Proč ne |
|---|---|
| Webhook přímo ze serveru (`ALERT_WEBHOOK_URL`) | Druhý mechanismus vedle Grafany; Grafana už alerting umí. |

## Důsledky

- Pravidla: data starší 5 min; chyba importu GTFS; nový rekordman nad `ALERT_DELAY_S` (metrika `flagged_trips`).
