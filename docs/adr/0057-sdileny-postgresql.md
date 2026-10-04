# ADR-0057: Sdílený PostgreSQL 17 bez rozšíření

- **Stav:** Přijato · nahrazuje ADR-0016
- **Oblast:** Architektura
- **Datum:** 2026-10-04

## Kontext

ADR-0016 zvolilo vlastní PostgreSQL 16. Homelab má sdílený PostgreSQL 17 (CT 124) pro další aplikace (ADR-0056).

## Rozhodnutí

Databáze `zasstojime` ve sdíleném PostgreSQL 17, bez rozšíření. `stop_events` dál dělené po měsících (deklarativní partitioning). Testy (Dev Services) i místní `docker-compose.yml` používají `postgres:17-alpine`.

## Zvažované alternativy

| Alternativa | Proč ne |
|---|---|
| Vlastní PostgreSQL 16 v aplikačním LXC | Další instance k údržbě a zálohování. |
| TimescaleDB | Zbytečná složitost (beze změny proti ADR-0016). |

## Důsledky

- Role `zasstojime` vlastní jen svou databázi; oddíly zakládá a maže aplikace sama (PartitionManager), žádná práva navíc nepotřebuje.
- Růst `stop_events` (jednotky GB měsíčně) sdílí disk s ostatními databázemi v CT 124; hlídá ho panel „Velikost DB“ a alert na zaplnění disku LXC v homelabu.
