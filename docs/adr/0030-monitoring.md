# ADR-0030: Monitoring: Micrometer → Prometheus → Grafana

- **Stav:** Přijato
- **Oblast:** Provoz
- **Datum:** 2026-10-02

## Kontext

Je potřeba hlídat, že sběr běží.

## Rozhodnutí

Server exportuje metriky (Micrometer/Prometheus); Grafana v homelabu jen přes VPN; alert při datech starších 5 min.
