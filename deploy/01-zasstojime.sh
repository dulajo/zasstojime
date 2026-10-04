#!/usr/bin/env bash
#
# Runtime serveru zasstojime v aplikačním LXC (docs/provoz.md, krok 3).
#
# Pouštěj jako root uvnitř aplikačního LXC (Debian 12 nebo 13). Idempotentní:
# připraví Javu, uživatele, adresáře a systemd unit a zkontroluje env soubor.
# Službu spustí, až je všechno na místě — fast-jar se nahrává z vývojového
# stroje.
#
#   ./01-zasstojime.sh
#
# Opakované spuštění je bezpečné: env soubor nepřepíše, jen řekne, co chybí,
# a když je všechno hotové, službu restartuje.
#
set -euo pipefail

APP_USER="${APP_USER:-zasstojime}"
APP_HOME="${APP_HOME:-/opt/zasstojime}"
# Server drží v paměti jízdní řád a stav dnešních a včerejších spojů (ADR-0038).
HEAP_MAX="${HEAP_MAX:-1g}"
HEAP_MIN="${HEAP_MIN:-256m}"
HTTP_PORT="${HTTP_PORT:-8080}"

log() { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33mpozor:\033[0m %s\n' "$*"; }
die() { printf '\033[1;31mchyba:\033[0m %s\n' "$*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || die "spusť jako root"
# SSH z macOS posílá LC_CTYPE=UTF-8, které Linux nezná.
export LC_ALL=C.UTF-8

# --- 1. Java 21 -------------------------------------------------------------
export DEBIAN_FRONTEND=noninteractive
log "instaluji závislosti"
apt-get update -qq
apt-get install -y -qq ca-certificates curl rsync >/dev/null

# Výstup nejdřív do proměnné: s pipefail by `| grep -q` způsobil SIGPIPE.
# LC_ALL=C, protože česky apt píše „Kandidát:“.
JDK_POLICY="$(LC_ALL=C apt-cache policy openjdk-21-jre-headless 2>/dev/null || true)"
if [[ $JDK_POLICY != *"Candidate: "[0-9]* ]]; then
  CODENAME="$(. /etc/os-release && echo "${VERSION_CODENAME:-}")"
  [[ -n "$CODENAME" ]] || die "openjdk-21 není v apt a nepodařilo se zjistit vydání Debianu"
  warn "openjdk-21 není v main, zapínám $CODENAME-backports"
  echo "deb http://deb.debian.org/debian $CODENAME-backports main" \
    > /etc/apt/sources.list.d/backports.list
  apt-get update -qq
fi
apt-get install -y -qq openjdk-21-jre-headless >/dev/null \
  || die "openjdk-21-jre-headless se nepodařilo nainstalovat"

JAVA_BIN="$(command -v java)"
log "java: $($JAVA_BIN -version 2>&1 | head -1)"

# --- 2. uživatel a adresáře -------------------------------------------------
if ! id -u "$APP_USER" >/dev/null 2>&1; then
  log "zakládám systémového uživatele $APP_USER"
  useradd -r -s /usr/sbin/nologin -d "$APP_HOME" "$APP_USER"
fi

log "připravuji $APP_HOME"
mkdir -p "$APP_HOME"/{bin,config}
chown -R "$APP_USER:$APP_USER" "$APP_HOME"
chmod 750 "$APP_HOME"
chmod 700 "$APP_HOME/config"

# --- 3. env soubor ----------------------------------------------------------
ENV_FILE="$APP_HOME/config/zasstojime.env"
if [[ ! -f "$ENV_FILE" ]]; then
  log "zakládám $ENV_FILE ze vzoru — vyplň ho"
  if [[ -f "$(dirname "$0")/zasstojime.env.example" ]]; then
    cp "$(dirname "$0")/zasstojime.env.example" "$ENV_FILE"
  else
    warn "vzor zasstojime.env.example vedle skriptu není, zakládám prázdný"
    : > "$ENV_FILE"
  fi
else
  log "$ENV_FILE už existuje, nechávám být"
fi
chown "$APP_USER:$APP_USER" "$ENV_FILE"
chmod 600 "$ENV_FILE"

# --- 4. systemd unit --------------------------------------------------------
log "zapisuji systemd unit"
cat > /etc/systemd/system/zasstojime.service <<EOF
# Spravuje deploy/01-zasstojime.sh z repozitáře zasstojime — ruční úpravy přepíše.
[Unit]
Description=Zasstojime (sběr dochvilnosti PID a API dashboardu)
Documentation=https://github.com/dulajo/zasstojime/blob/master/docs/provoz.md
# Flyway potřebuje při startu databázi; Restart=always dokryje, kdyby DB LXC
# naskočil později.
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=$APP_USER
Group=$APP_USER
WorkingDirectory=$APP_HOME
EnvironmentFile=$ENV_FILE
# Bez lokalizace vypisuje Java diakritiku jako "?"; časy v logu v pražském čase.
Environment=LANG=C.UTF-8
Environment=TZ=Europe/Prague
ExecStart=$JAVA_BIN -Xms$HEAP_MIN -Xmx$HEAP_MAX -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -jar $APP_HOME/bin/quarkus-run.jar
Restart=always
RestartSec=10s
TimeoutStopSec=30
SuccessExitStatus=0 143

# Izolace. V neprivilegovaném LXC můžou některé volby selhat — když unit
# nenaběhne s chybou o namespace, zakomentuj blok a nahlas to.
ProtectSystem=full
ProtectHome=true
PrivateTmp=true
PrivateDevices=true
NoNewPrivileges=true
RestrictSUIDSGID=true
LockPersonality=true

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable -q zasstojime.service

# --- 5. kontrola env souboru ------------------------------------------------
# Env soubor čteme, ne sourcujeme: patří službě a shell by v něm vykonal cokoli.
env_get() {
  local value
  value="$(sed -n "s/^[[:space:]]*$1[[:space:]]*=//p" "$ENV_FILE" | tail -1)"
  value="${value#"${value%%[![:space:]]*}"}"
  value="${value%"${value##*[![:space:]]}"}"
  if [[ $value == \"*\" || $value == \'*\' ]]; then
    value="${value:1:${#value}-2}"
  fi
  printf '%s' "$value"
}

is_placeholder() {
  case "$1" in
    '' | *zmen-me*) return 0 ;;
  esac
  return 1
}

MISSING=()
if [[ ! -f "$APP_HOME/bin/quarkus-run.jar" ]]; then
  MISSING+=("fast-jar v $APP_HOME/bin/ (nahraj z vývojového stroje, viz níže)")
fi
for key in GOLEMIO_API_KEY DATABASE_URL DATABASE_USER DATABASE_PASSWORD; do
  if is_placeholder "$(env_get "$key")"; then
    MISSING+=("$key v $ENV_FILE (chybí, nebo je tam hodnota ze vzoru)")
  fi
done

# --- 6. dosah na okolní služby ----------------------------------------------
# Jen varování: bez databáze služba nenaběhne, bez Golemia běží, ale nic nesbírá.
tcp_ok() { timeout 5 bash -c 'exec 3<>"/dev/tcp/$1/$2"' _ "$1" "$2" 2>/dev/null; }
check_reach() {
  if tcp_ok "$1" "$2"; then
    log "dosažitelné: $3 ($1:$2)"
  else
    warn "$3 ($1:$2) není z kontejneru dosažitelné"
  fi
}

JDBC_URL="$(env_get DATABASE_URL)"
if [[ $JDBC_URL =~ ^jdbc:postgresql://([^/:]+)(:([0-9]+))?/ ]]; then
  check_reach "${BASH_REMATCH[1]}" "${BASH_REMATCH[3]:-5432}" "PostgreSQL"
fi
check_reach data.pid.cz 443 "jízdní řád PID"

# Golemio: nestačí port, klíč musí projít. Hlavička jde přes soubor, aby klíč
# neskončil v `ps`.
GOLEMIO_KEY="$(env_get GOLEMIO_API_KEY)"
if ! is_placeholder "$GOLEMIO_KEY"; then
  AUTH_HEADER="$(mktemp)"
  chmod 600 "$AUTH_HEADER"
  printf 'X-Access-Token: %s\n' "$GOLEMIO_KEY" > "$AUTH_HEADER"
  GOLEMIO_STATUS="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 15 -H @"$AUTH_HEADER" \
    'https://api.golemio.cz/v2/vehiclepositions?limit=1' 2>/dev/null || true)"
  rm -f "$AUTH_HEADER"
  case "$GOLEMIO_STATUS" in
    200) log "Golemio odpovídá a API klíč platí" ;;
    401 | 403) MISSING+=("platný GOLEMIO_API_KEY (Golemio vrátilo $GOLEMIO_STATUS)") ;;
    *) warn "Golemio vrátilo '${GOLEMIO_STATUS:-nic}' — sběr bez něj nepoběží" ;;
  esac
fi
unset GOLEMIO_KEY

echo
if (( ${#MISSING[@]} )); then
  log "runtime je připravený, služba je enabled, ale zatím nespuštěná."
  echo "Chybí ještě:"
  printf '  - %s\n' "${MISSING[@]}"
  echo
  echo "Env soubor:  nano $ENV_FILE"
  echo
  echo "Aplikace z vývojového stroje (v kořeni repozitáře):"
  echo "  (cd server && ./gradlew clean build)"
  echo "  rsync -avz --delete server/build/quarkus-app/ root@$(hostname -I | awk '{print $1}'):$APP_HOME/bin/"
  echo
  echo "Pak spusť tenhle skript znovu, službu nastartuje sám."
  exit 0
fi

# --- 7. start ---------------------------------------------------------------
log "všechno je na místě, (re)startuji službu"
chown -R "$APP_USER:$APP_USER" "$APP_HOME/bin"
systemctl restart zasstojime.service

# Metriky odpovídají hned po startu HTTP serveru; /api/health až po prvním
# úspěšném cyklu, kterému při prvním startu předchází import jízdního řádu.
log "čekám, až služba odpoví na /q/metrics (nejvýš 90 s)"
for _ in $(seq 1 45); do
  if curl -fsS --max-time 2 "http://127.0.0.1:$HTTP_PORT/q/metrics" >/dev/null 2>&1; then
    log "běží, metriky odpovídají na http://127.0.0.1:$HTTP_PORT/q/metrics"
    log "čekám na první úspěšný cyklus sběru (nejvýš 3 min)"
    for _ in $(seq 1 90); do
      if curl -fsS --max-time 2 "http://127.0.0.1:$HTTP_PORT/api/health" >/dev/null 2>&1; then
        log "hotovo, sběr běží: $(curl -fsS "http://127.0.0.1:$HTTP_PORT/api/health")"
        exit 0
      fi
      sleep 2
    done
    warn "/api/health po 3 min stále neodpovídá 200, posledních 20 řádků logu:"
    journalctl -u zasstojime -n 20 --no-pager || true
    die "služba běží, ale sběr zatím neprošel — oprav příčinu podle logu (docs/provoz.md – Když sběr stojí)"
  fi
  sleep 2
done

warn "služba do 90 s neodpověděla, posledních 40 řádků logu:"
journalctl -u zasstojime -n 40 --no-pager || true
die "služba nenaběhla — oprav příčinu podle logu a spusť skript znovu"
