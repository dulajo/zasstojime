# Provoz: nasazení do homelabu a ověření

Postup pro krok 1a-4 (spec, kapitola 8). Server běží ve stávající infrastruktuře homelabu
(ADR-0056) stejně jako inbox-triage:

| Kde | Co |
|---|---|
| Nový neprivilegovaný LXC `zasstojime` (Debian 13) | server jako systemd služba, Java 21, fast-jar |
| `postgres-db` (CT 124, `10.140.0.135`) | databáze `zasstojime` ve sdíleném PostgreSQL 17 (ADR-0057) |
| `observability` (CT 119, `10.140.0.98`) | Prometheus stahuje `/q/metrics`, pravidla a dashboard v Grafaně, logy v Loki |

Historie dat začíná prvním úspěšným cyklem sběru, proto je nasazení prioritou.
Skripty v [`deploy/`](../deploy) jsou idempotentní; opakované spuštění jen srovná stav.

## Krok 1: aplikační LXC

| RAM | Disk | CPU |
|---|---|---|
| 2 GB / swap 512 MB | 8 GB (data jsou v CT 124) | 2 |

- Adresa musí být rezervovaná nebo statická (potřebuje ji `pg_hba.conf` i Prometheus).
- Odchozí HTTPS na `api.golemio.cz` a `data.pid.cz`. Příchozí jen `8080` pro Prometheus z `10.140.0.98`.
- **Nesting je povinný** (Debian 13 a journald, podrobně `inbox-triage/docs/deploy.md`):
  `pct set <CT> --features nesting=1`, pak `pct reboot <CT>`.
- Start s hostem po databázi: `pct set <CT> --onboot 1 --startup order=4`
  (stejně jako inbox-triage a Paperless, viz `homelab/proxmox`).
- Kartu v homelab repu (`INVENTAR.md`, `postgres-db/README.md`) doplnit o CT a IP.

## Krok 2: databáze (CT 124)

V `postgres-db` jako root, se skriptem z repa `homelab`:

```bash
DB_NAME=zasstojime ALLOWED_HOSTS="<IP-ZASSTOJIME>/32" ./add-database.sh
```

Založí roli a databázi `zasstojime` a blok v `pg_hba.conf` jen pro adresu LXC. Vygenerované
heslo vypíše jednou, patří do env souboru v kroku 3. Schéma založí server sám při startu (Flyway).

## Krok 3: runtime služby (aplikační LXC)

Zkopíruj do kontejneru `deploy/01-zasstojime.sh` **i** `deploy/zasstojime.env.example`
(musí ležet vedle sebe) a spusť jako root:

```bash
./01-zasstojime.sh
```

Skript nainstaluje Javu 21, založí uživatele `zasstojime` a `/opt/zasstojime/{bin,config}`,
zapíše systemd unit `zasstojime.service` (halda `-Xmx1g`, `TZ=Europe/Prague`) a z předlohy
založí `/opt/zasstojime/config/zasstojime.env` s právy `0600`. Ověří dosah na databázi
a `data.pid.cz` a platnost klíče Golemia. Dokud něco chybí, službu nespustí a vypíše seznam.

Vyplň env soubor (`GOLEMIO_API_KEY` z [api.golemio.cz/api-keys](https://api.golemio.cz/api-keys),
`DATABASE_PASSWORD` z kroku 2) a nahraj aplikaci z vývojového stroje:

```bash
(cd server && ./gradlew clean build)      # testy potřebují Docker
rsync -avz --delete server/build/quarkus-app/ root@<IP-ZASSTOJIME>:/opt/zasstojime/bin/
```

Bez SSH do kontejneru přes Proxmox host (`10.140.0.63`) a `pct push`, stejně jako
u inbox-triage:

```bash
# vývojový stroj (macOS)
COPYFILE_DISABLE=1 tar czf /tmp/zasstojime-app.tgz -C server build/quarkus-app -C .. deploy
scp /tmp/zasstojime-app.tgz root@10.140.0.63:/root/
# Proxmox host
pct push <CT> /root/zasstojime-app.tgz /root/zasstojime-app.tgz
# aplikační LXC (pct enter <CT>)
rm -rf /root/release && mkdir /root/release
tar xzf /root/zasstojime-app.tgz -C /root/release --warning=no-unknown-keyword
rsync -a --delete /root/release/build/quarkus-app/ /opt/zasstojime/bin/
/root/release/deploy/01-zasstojime.sh
```

Pak skript spusť znovu. Službu nastartuje, počká na `/q/metrics` a pak na první úspěšný
cyklus (`/api/health` vrátí 200). První start stáhne jízdní řád (desítky sekund).

```bash
systemctl status zasstojime
journalctl -u zasstojime -f          # „sběr spuštěn“, pak každých 30 s cyklus
curl -s localhost:8080/api/health    # {"status":"ok", ...}
```

## Krok 4: metriky, logy a alerty (observability, CT 119)

### Metriky

Do `scrape_configs` v `/etc/prometheus/prometheus.yml` přidej obsah
[`deploy/observability/prometheus-scrape.yml`](../deploy/observability/prometheus-scrape.yml)
s adresou LXC místo `<IP-ZASSTOJIME>`:

```bash
promtool check config /etc/prometheus/prometheus.yml
systemctl reload prometheus
```

V `http://10.140.0.98:9090/targets` musí být job `zasstojime` ve stavu *UP*.

### Logy

V aplikačním LXC jako root (nainstaluje Grafana Alloy, journal služby posílá do Loki
s labely `job="zasstojime"`, `host`, `level`):

```bash
./02-observability.sh
```

### Alerty

```bash
# vývojový stroj
scp deploy/observability/zasstojime-rules.yaml root@10.140.0.98:/etc/grafana/provisioning/alerting/
ssh root@10.140.0.98 systemctl restart grafana-server
```

Výchozí notification policy posílá všechno na kontaktní bod `telegram`, směrování není potřeba.

| Alert | Podmínka | Čekání | Závažnost |
|---|---|---|---|
| `ZasstojimeDown` | `up{job="zasstojime"}` < 1, i bez dat | 5 min | critical |
| `ZasstojimeNotCollecting` | stáří dat > 3 min (před prvním cyklem doba běhu služby) | 2 min | critical |
| `ZasstojimeCycleErrors` | za hodinu selhalo > 5 % cyklů; zhasne, až hodinové okno klesne pod 5 % | 10 min | warning |
| `ZasstojimeGtfsImportFailed` | selhání importu jízdního řádu za poslední hodinu | — | warning |
| `ZasstojimeGtfsStale` | poslední úspěšný import starší než 30 h | 10 min | warning |
| `ZasstojimeRecordUnverified` | přibyl spoj nad `ALERT_DELAY_S` (ADR-0055) | — | info |

### Dashboard

V Grafaně *Dashboards → New → Import* nahraj
[`deploy/observability/zasstojime-dashboard.json`](../deploy/observability/zasstojime-dashboard.json)
a vyber zdroje Prometheus a Loki. Po změně znovu naimportovat (přepíše se podle `uid`).

## AC-07: 24 h provozu

**Bez pádu:** `NRestarts=0` a služba běží přes 24 h.

```bash
systemctl show zasstojime -p NRestarts -p ActiveEnterTimestamp
```

**Úspěšnost sběru ≥ 99 %** (v CT 124, nebo panel „Úspěšnost cyklů (24 h)“):

```bash
runuser -u postgres -- psql -d zasstojime -c "
SELECT count(*) AS behu,
       round(100.0 * count(*) FILTER (WHERE ok) / count(*), 2) AS uspesnost_pct,
       max(gap) AS nejdelsi_mezera
FROM (SELECT ok, started_at - lag(started_at) OVER (ORDER BY started_at) AS gap
      FROM ingest_runs WHERE started_at > now() - interval '24 hours') r;"
```

Při intervalu 30 s je za 24 h zhruba 2880 běhů.

**Alert ověřený vypnutím sítě:** odpojit LXC od internetu, databáze a Prometheus v LAN musí
zůstat dostupné. V aplikačním LXC jako root (přes `pct enter <CT>`; SSH přes VPN by se
odpojilo) dočasně zahodit výchozí trasu:

```bash
ip route show default                 # zapsat si bránu a rozhraní
ip route del default
# do ~5 min přijde do Telegramu ZasstojimeNotCollecting
ip route add default via <BRÁNA> dev eth0
# do ~2 min přijde Resolved; ZasstojimeCycleErrors může svítit ještě do hodiny
```

Kdyby se trasu nepodařilo vrátit, obnoví ji `pct reboot <CT>`.
Mezera v datech se do souhrnu dne promítne jako `incomplete`, jen pokud je delší než 30 min.

Po splnění AC-07 aktualizujte stav Fáze 1a v README.

## AC-08: kontrola proti realitě

Vybrat 3 spoje, které právě jedou, a porovnat zpoždění s odjezdovou tabulí nebo aplikací
PID Lítačka. V CT 124:

```bash
runuser -u postgres -- psql -d zasstojime -c "
SELECT trip_id, route_short, headsign, last_delay, last_seen_at
FROM trips_daily
WHERE service_date = current_date AND status = 'running' AND category <> 'metro'
ORDER BY random() LIMIT 3;"
```

Pro každý spoj poslední změřené odjezdy (`<TRIP_ID>` doplnit):

```bash
runuser -u postgres -- psql -d zasstojime -c "
SELECT e.stop_seq, s.name, e.planned_dep AT TIME ZONE 'Europe/Prague' AS plan, e.dep_delay
FROM stop_events e JOIN stops s USING (stop_id)
WHERE e.service_date = current_date AND e.trip_id = '<TRIP_ID>' AND e.dep_delay IS NOT NULL
ORDER BY e.stop_seq DESC LIMIT 5;"
```

Rozdíl do 1 min je v pořádku: aplikace ukazují zpoždění v celých minutách. Výsledek zapsat
do `docs/faze0.md` nebo do issue.

## Když sběr stojí

1. `systemctl status zasstojime` a `curl -s localhost:8080/api/health`.
2. `journalctl -u zasstojime -n 100` (nebo Loki `{job="zasstojime"}`): hledat „Cyklus sběru selhal“.
3. Panel „Chyby Golemia“: `http_401`/`http_403` = API klíč, `timeout`/`io` = síť nebo Golemio.
4. Chyba `DB:` v logu = PostgreSQL v CT 124 (`ss -ltn | grep 5432`, `pg_hba.conf`). Neuložené
   průjezdy server drží v paměti a uloží je po obnovení; zahodí je až po ~2 h
   (panel „Zahozené odjezdy“, důvod `db_unavailable`).

## Neověřený rekordman

`ZasstojimeRecordUnverified` znamená spoj se zpožděním nad 2 h. Na webu je označený „Neověřeno“
(ADR-0055). Ověřit proti zprávám DPP o mimořádnostech a v CT 124 buď potvrdit
(`UPDATE trips_daily SET verified = true WHERE service_date = … AND trip_id = …`), nebo vyřadit
(`SET excluded = true`). Sběr tyto sloupce nepřepisuje a `/api/day/{date}/record` je čte z DB.

## Aktualizace

```bash
# vývojový stroj
(cd server && ./gradlew clean build)
rsync -avz --delete server/build/quarkus-app/ root@<IP-ZASSTOJIME>:/opt/zasstojime/bin/
# aplikační LXC
./01-zasstojime.sh
```

Restart historii nepřeruší: rozjeté spoje se obnoví z DB (FR-14), migrace běží při startu.
Před aktualizací, která mění schéma, udělat dump databáze (postup v `homelab/postgres-db`).

## Zatím chybí

- Denní `pg_dump` mimo homelab (ADR-0031); teď jen PBS celého CT 124.
- Cloudflare Tunnel pro veřejné API (ADR-0026, krok 1b-3).
