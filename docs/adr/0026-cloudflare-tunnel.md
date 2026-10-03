# ADR-0026: Veřejný přístup jen přes Cloudflare Tunnel

- **Stav:** Přijato
- **Oblast:** Provoz
- **Datum:** 2026-10-02

## Kontext

Homelab nemá být přímo vystavený do internetu. NPM a WireGuard zůstávají pro správu.

## Rozhodnutí

API je veřejné jen přes Cloudflare Tunnel (odchozí spojení). Správa (Grafana, DB) jen přes WireGuard + NPM.

## Zvažované alternativy

| Alternativa | Proč ne |
|---|---|
| NPM veřejně / port forwarding | Otevřený port, viditelná domácí IP. |
