# ADR-0056: Nasazení do stávající infrastruktury homelabu

- **Stav:** Přijato · nahrazuje ADR-0025
- **Oblast:** Provoz
- **Datum:** 2026-10-04

## Kontext

ADR-0025 počítalo s vlastní izolovanou VM v oddělené VLAN a Docker Compose (server, PostgreSQL, cloudflared). Homelab už má sdílený PostgreSQL (CT 124), centrální Prometheus, Grafanu a Loki s alerty do Telegramu (CT 119) a ustálený postup nasazení Java aplikací (inbox-triage). Vlastní kopie každé z těchto věcí by znamenala další věci k údržbě a druhé místo, kam chodí alerty.

## Rozhodnutí

- Server běží jako systemd služba (Java 21, fast-jar) ve vlastním neprivilegovaném LXC. Nasazení stejně jako u inbox-triage: `deploy/01-zasstojime.sh`, aplikace se nahrává z vývojového stroje.
- Databáze `zasstojime` ve sdíleném PostgreSQL 17 (ADR-0057), zakládá ji `homelab/postgres-db/add-database.sh`.
- Metriky stahuje centrální Prometheus, pravidla a dashboard jsou v `deploy/observability/` a nahrávají se do centrální Grafany. Logy posílá Alloy do Loki (`deploy/02-observability.sh`).
- Veřejné API později přes cloudflared v aplikačním LXC (ADR-0026).

## Zvažované alternativy

| Alternativa | Proč ne |
|---|---|
| Vlastní VM s Docker Compose (ADR-0025) | Duplikuje databázi, monitoring i alerty, které homelab už má. |
| Vlastní Grafana a Prometheus v Compose (profil `monitoring`) | Druhý Telegram bot a druhé místo pro pravidla. |

## Důsledky

- `docker-compose.yml` zůstává pro místní běh celé sestavy, v homelabu se nepoužívá.
- Aplikační LXC nemá vlastní VLAN; přístup do databáze omezuje `pg_hba.conf` na adresu LXC. Veřejné API půjde jen přes Cloudflare Tunnel (odchozí spojení), žádný port se ven neotevírá.
- Záloha databáze je zatím jen PBS celého CT 124 (crash-konzistentní), stejně jako u ostatních databází. Denní `pg_dump` mimo homelab (ADR-0031) zbývá.
- Alerty jdou na kontaktní bod `telegram` centrální Grafany; záložní e-mail z ADR-0030 tam zatím není.
- Výpadek proudu či internetu doma = díra v datech (beze změny).
