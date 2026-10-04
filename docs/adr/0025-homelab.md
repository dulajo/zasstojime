# ADR-0025: Server v homelabu

- **Stav:** Nahrazeno ADR-0056
- **Oblast:** Provoz
- **Datum:** 2026-10-02

## Kontext

Autor má homelab; VPS je dražší.

## Rozhodnutí

Docker Compose (server, PostgreSQL, cloudflared) v izolované VM/LXC v oddělené VLAN.

## Zvažované alternativy

| Alternativa | Proč ne |
|---|---|
| VPS | Možné kdykoliv později; Compose je přenositelný. |

## Důsledky

- Výpadek proudu či internetu doma = díra v datech.
