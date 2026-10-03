# Provoz: nasazení do homelabu a ověření

Postup pro krok 1a-4 (spec, kapitola 8): server, PostgreSQL a monitoring v izolované VM, zatím bez
veřejného přístupu. Historie dat začíná prvním úspěšným cyklem sběru, proto je nasazení prioritou.

## 1. Příprava VM

- Docker Engine s Compose v2 (`docker compose version`).
- JDK 21 a git (build serveru běží na VM; testy potřebují Docker, který tam už je).
- Odchozí přístup na `api.golemio.cz`, `data.pid.cz` a `api.telegram.org`. Příchozí porty nejsou potřeba.

```bash
git clone https://github.com/dulajo/zasstojime.git && cd zasstojime
cp .env.example .env && chmod 600 .env
```

## 2. Konfigurace `.env`

| Proměnná | Kde ji vzít |
|---|---|
| `GOLEMIO_API_KEY` | [api.golemio.cz/api-keys](https://api.golemio.cz/api-keys) |
| `DATABASE_PASSWORD` | Vygenerovat, např. `openssl rand -base64 24` |
| `TELEGRAM_BOT_TOKEN` | Telegram: `@BotFather` → `/newbot` |
| `TELEGRAM_CHAT_ID` | Napsat botovi libovolnou zprávu, pak `curl -s https://api.telegram.org/bot<TOKEN>/getUpdates` → `message.chat.id` |
| `GRAFANA_BIND` | Adresa VM ve správcovské síti (WireGuard/NPM). Výchozí `127.0.0.1` = jen z VM. |

Bez `TELEGRAM_BOT_TOKEN` a `TELEGRAM_CHAT_ID` Grafana nenaběhne (v logu „could not find Bot Token“).
Je to záměr: monitoring bez alertů by jen dával falešný pocit jistoty.

Záložní e-mail pro alerty (ADR-0030) je v `deploy/monitoring/grafana/alerting/contact-points.yml`
zakomentovaný. Před odkomentováním doplňte ke službě `grafana` proměnné `GF_SMTP_ENABLED=true`,
`GF_SMTP_HOST`, `GF_SMTP_USER`, `GF_SMTP_PASSWORD`, `GF_SMTP_FROM_ADDRESS` a do `.env` `ALERT_EMAIL`.

## 3. Build a start

```bash
cd server && ./gradlew build && cd ..
docker compose --profile monitoring up -d --build
docker compose logs -f server      # čekat na „sběr spuštěn“
curl -s localhost:8080/api/health  # {"status":"ok", ...}
```

První start stáhne jízdní řád (desítky sekund). Do prvního úspěšného cyklu vrací `/api/health` 503.
Pokud by start bez úspěšného cyklu trval přes 5 min, ozve se alert „Sběr stojí“.

Grafana: `http://<GRAFANA_BIND>:3001`, první přihlášení `admin` / `admin` a hned změna hesla.

- Dashboard **Zasstojime → Zasstojime – provoz**: stáří dat, úspěšnost cyklů, vozidla, chyby Golemia.
- **Alerting → Contact points → provoz → Test**: do Telegramu musí přijít zkušební zpráva.

### Alerty

| Alert | Podmínka | Závažnost |
|---|---|---|
| Sběr stojí | Poslední úspěšný cyklus starší než ~5 min, nebo Prometheus server nevidí | critical |
| Sběr často selhává | Za hodinu selhalo > 5 % cyklů (zhasne, až hodinové okno klesne pod 5 %) | warning |
| Import jízdního řádu selhal | Selhání importu GTFS za poslední hodinu | warning |
| Jízdní řád je starý | Poslední úspěšný import starší než 30 h | warning |
| Rekordman čeká na ověření | Přibyl spoj nad `ALERT_DELAY_S` (ADR-0055) | info |

Pravidla jsou v `deploy/monitoring/grafana/alerting/rules.yml`; v Grafaně je nejde upravit, změna jde přes git.

## 4. AC-07: 24 h provozu

Po 24 h od startu ověřit tři věci.

**Bez pádu:** `RestartCount` musí být 0 a `StartedAt` starší než 24 h.

```bash
docker inspect -f '{{.RestartCount}} {{.State.StartedAt}}' $(docker compose ps -q server)
```

**Úspěšnost sběru ≥ 99 %:**

```bash
docker compose exec db psql -U zasstojime -d zasstojime -c "
SELECT count(*) AS behu,
       round(100.0 * count(*) FILTER (WHERE ok) / count(*), 2) AS uspesnost_pct,
       max(gap) AS nejdelsi_mezera
FROM (SELECT ok, started_at - lag(started_at) OVER (ORDER BY started_at) AS gap
      FROM ingest_runs WHERE started_at > now() - interval '24 hours') r;"
```

Při intervalu 30 s je za 24 h zhruba 2880 běhů. Stejné číslo ukazuje panel „Úspěšnost cyklů (24 h)“.

**Alert ověřený vypnutím sítě:** serveru se zablokuje odchozí provoz mimo síť Compose, databáze
i Grafana dál fungují. Na VM (jako root):

```bash
NET=$(docker inspect -f '{{range $k, $v := .NetworkSettings.Networks}}{{$k}}{{end}}' $(docker compose ps -q server))
SUBNET=$(docker network inspect -f '{{(index .IPAM.Config 0).Subnet}}' "$NET")
IP=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' $(docker compose ps -q server))
iptables -I DOCKER-USER -s "$IP" ! -d "$SUBNET" -j REJECT
# do ~6 min přijde do Telegramu „Sběr stojí“
iptables -D DOCKER-USER -s "$IP" ! -d "$SUBNET" -j REJECT
# do ~2 min přijde zpráva o vyřešení
```

Mezera v datech se do souhrnu dne promítne jako `incomplete`, jen pokud je delší než 30 min.

Po splnění AC-07 aktualizujte stav Fáze 1a v README.

## 5. AC-08: kontrola proti realitě

Vybrat 3 spoje, které právě jedou, a porovnat zpoždění s odjezdovou tabulí nebo aplikací PID Lítačka.

```bash
docker compose exec db psql -U zasstojime -d zasstojime -c "
SELECT trip_id, route_short, headsign, last_delay, last_seen_at
FROM trips_daily
WHERE service_date = current_date AND status = 'running' AND category <> 'metro'
ORDER BY random() LIMIT 3;"
```

Pro každý vybraný spoj poslední změřené odjezdy (`<TRIP_ID>` doplnit):

```bash
docker compose exec db psql -U zasstojime -d zasstojime -c "
SELECT e.stop_seq, s.name, e.planned_dep AT TIME ZONE 'Europe/Prague' AS plan, e.dep_delay
FROM stop_events e JOIN stops s USING (stop_id)
WHERE e.service_date = current_date AND e.trip_id = '<TRIP_ID>' AND e.dep_delay IS NOT NULL
ORDER BY e.stop_seq DESC LIMIT 5;"
```

Rozdíl do 1 min je v pořádku: aplikace ukazují zpoždění v celých minutách. Výsledek
zapište do `docs/faze0.md` nebo do issue.

## Když sběr stojí

1. `curl -s localhost:8080/api/health` a `docker compose ps`.
2. `docker compose logs --tail 100 server`: hledat „Cyklus sběru selhal“.
3. Panel „Chyby Golemia“: `http_401`/`http_403` = API klíč, `timeout`/`io` = síť nebo Golemio.
4. Chyba `DB:` v logu = PostgreSQL (`docker compose logs db`). Neuložené průjezdy server drží
   v paměti a uloží je po obnovení; zahodí je až po ~2 h (panel „Zahozené odjezdy“, `db_unavailable`).

## Aktualizace

```bash
git pull
cd server && ./gradlew build && cd ..
docker compose --profile monitoring up -d --build
```

Restart serveru historii nepřeruší: rozjeté spoje se obnoví z DB (FR-14). Migrace DB běží při startu.

## Zatím chybí

- Denní `pg_dump` mimo homelab (ADR-0031).
- Cloudflare Tunnel pro veřejné API (ADR-0026, krok 1b-3).
