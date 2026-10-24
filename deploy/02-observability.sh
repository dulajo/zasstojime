#!/usr/bin/env bash
#
# Logy serveru do centrálního Loki na observability (docs/provoz.md, krok 4).
#
# Pouštěj jako root v aplikačním LXC. Nainstaluje Grafana Alloy z oficiálního
# apt repozitáře Grafany, nechá ho číst journal jednotky zasstojime.service
# a posílat ho do Loki. Idempotentní: opakované spuštění přepíše konfiguraci
# Alloye a ověří, že logy dorazily. Postup je stejný jako u inbox-triage
# (deploy/04-observability.sh tam).
#
#   ./02-observability.sh
#
# Metriky tenhle skript neřeší — Prometheus na observability si je stahuje sám
# z /q/metrics, viz deploy/observability/prometheus-scrape.yml.
#
set -euo pipefail

LOKI_URL="${LOKI_URL:?Nastav LOKI_URL, např. http://<OBS_IP>:3100}"
LOG_JOB="${LOG_JOB:-zasstojime}"
LOG_HOST="${LOG_HOST:-$(hostname -s)}"
SERVICE_UNIT="${SERVICE_UNIT:-zasstojime.service}"
ALLOY_CONFIG=/etc/alloy/config.alloy

log() { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33mpozor:\033[0m %s\n' "$*"; }
die() { printf '\033[1;31mchyba:\033[0m %s\n' "$*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || die "spusť jako root"
export LC_ALL=C.UTF-8

# --- 1. předpoklady ---------------------------------------------------------
# Bez běžícího journaldu (v Debianu 13 bez nestingu) by Alloy neměl co číst.
systemctl is-active --quiet systemd-journald \
  || die "systemd-journald neběží — zapni kontejneru nesting (docs/provoz.md, krok 1)"
curl -fsS --max-time 5 "$LOKI_URL/ready" >/dev/null \
  || die "Loki na $LOKI_URL/ready neodpovídá"
log "Loki na $LOKI_URL odpovídá"

# --- 2. Grafana Alloy -------------------------------------------------------
export DEBIAN_FRONTEND=noninteractive
log "instaluji Grafana Alloy"
apt-get update -qq
apt-get install -y -qq ca-certificates curl gpg >/dev/null

KEYRING=/etc/apt/keyrings/grafana.gpg
if [[ ! -s $KEYRING ]]; then
  mkdir -p /etc/apt/keyrings
  curl -fsSL https://apt.grafana.com/gpg.key | gpg --dearmor > "$KEYRING.tmp"
  mv "$KEYRING.tmp" "$KEYRING"
  chmod 644 "$KEYRING"
fi
echo "deb [signed-by=$KEYRING] https://apt.grafana.com stable main" \
  > /etc/apt/sources.list.d/grafana.list
apt-get update -qq
apt-get install -y -qq alloy >/dev/null
log "alloy: $(alloy --version 2>&1 | head -1)"

# Journal smí číst root a skupina systemd-journal.
if ! id -nG alloy | tr ' ' '\n' | grep -qx systemd-journal; then
  usermod -aG systemd-journal alloy
fi

# --- 3. konfigurace ---------------------------------------------------------
# Quoted heredoc: zpětná lomítka v regexech zůstanou, jak jsou. Proměnné se
# doplní až potom přes zástupné řetězce.
log "zapisuji $ALLOY_CONFIG"
NEW_CONFIG="$(mktemp)"
trap 'rm -f "$NEW_CONFIG"' EXIT
cat > "$NEW_CONFIG" <<'EOF'
// Spravuje deploy/02-observability.sh z repozitáře zasstojime — ruční úpravy přepíše.

loki.relabel "journal" {
  forward_to = []

  rule {
    source_labels = ["__journal__systemd_unit"]
    target_label  = "unit"
  }
}

loki.source.journal "zasstojime" {
  matches       = "_SYSTEMD_UNIT=__UNIT__"
  max_age       = "12h"
  relabel_rules = loki.relabel.journal.rules
  forward_to    = [loki.process.zasstojime.receiver]
}

loki.process "zasstojime" {
  // Až tady: loki.source.journal si job přepíše na název komponenty.
  stage.static_labels {
    values = { job = "__JOB__", host = "__HOST__" }
  }

  // Journal dává výstup po řádcích; stack trace se slepí k řádku s časem.
  // Formát: quarkus.log.console.format v application.properties.
  stage.multiline {
    firstline     = "^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3} "
    max_wait_time = "3s"
  }

  // "2026-10-04 10:30:31.683 INFO  [kategorie] ..." -> level="info"
  stage.regex {
    expression = "^\\S+ \\S+ (?P<level>[A-Z]+)\\s"
  }

  stage.template {
    source   = "level"
    template = "{{ ToLower .Value }}"
  }

  stage.labels {
    values = { level = "" }
  }

  forward_to = [loki.write.observability.receiver]
}

loki.write "observability" {
  endpoint {
    url = "__LOKI_URL__/loki/api/v1/push"
  }
}
EOF
sed -i \
  -e "s#__UNIT__#$SERVICE_UNIT#g" \
  -e "s#__JOB__#$LOG_JOB#g" \
  -e "s#__HOST__#$LOG_HOST#g" \
  -e "s#__LOKI_URL__#${LOKI_URL%/}#g" \
  "$NEW_CONFIG"

# Syntaxi zkontrolujeme dřív, než přepíšeme funkční konfiguraci.
alloy fmt "$NEW_CONFIG" >/dev/null || die "vygenerovaná konfigurace Alloye není platná"
CONFIG_CHANGED=""
cmp -s "$NEW_CONFIG" "$ALLOY_CONFIG" 2>/dev/null || CONFIG_CHANGED=1
install -m 644 "$NEW_CONFIG" "$ALLOY_CONFIG"

# --- 4. start ---------------------------------------------------------------
log "startuji alloy"
systemctl enable -q alloy
systemctl stop alloy
# Se změněnou konfigurací se journal přečte znovu za posledních max_age, aby
# v Loki byla i historie pod novými labely; duplicity Loki zahodí.
if [[ -n $CONFIG_CHANGED ]]; then
  find /var/lib/alloy -path '*loki.source.journal.zasstojime*' -name 'positions.yml' -delete 2>/dev/null || true
fi
systemctl start alloy

for _ in $(seq 1 15); do
  if curl -fsS --max-time 2 http://127.0.0.1:12345/-/ready >/dev/null 2>&1; then
    break
  fi
  sleep 2
done
if ! systemctl is-active --quiet alloy; then
  journalctl -u alloy -n 30 --no-pager || true
  die "alloy nenaběhl — oprav příčinu podle logu a spusť skript znovu"
fi
log "alloy běží"

# --- 5. dorazily logy? ------------------------------------------------------
log "ověřuji, že logy dorazily do Loki (nejvýš 60 s)"
QUERY="{job=\"$LOG_JOB\", host=\"$LOG_HOST\"}"
START_NS="$(( $(date +%s) - 43200 ))000000000"
for _ in $(seq 1 30); do
  RESULT="$(curl -fsS -G --max-time 5 "${LOKI_URL%/}/loki/api/v1/query_range" \
    --data-urlencode "query=$QUERY" \
    --data-urlencode "start=$START_NS" \
    --data-urlencode "limit=1" 2>/dev/null || true)"
  if [[ -n $RESULT && $RESULT != *'"result":[]'* ]]; then
    log "hotovo, v Grafaně (Explore → Loki): $QUERY"
    exit 0
  fi
  sleep 2
done

warn "v Loki zatím nic není; stav Alloye:"
journalctl -u alloy -n 30 --no-pager || true
die "logy s {job=\"$LOG_JOB\"} zatím nedorazily — když služba za 12 h nic nezalogovala, restartuj ji (systemctl restart zasstojime) a spusť skript znovu"
