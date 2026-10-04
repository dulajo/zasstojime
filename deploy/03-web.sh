#!/usr/bin/env bash
#
# Náhled webu v aplikačním LXC (docs/provoz.md, krok 5).
#
# Pouštěj jako root v aplikačním LXC. Nainstaluje nginx, který na portu 80
# servíruje statický web z /var/www/zasstojime a /api/ přeposílá serveru na
# 127.0.0.1:8080. Web a API jsou tak na jedné adrese a CORS není potřeba.
# Veřejný web bude na Cloudflare (ADR-0027); tohle je náhled pro LAN a VPN.
#
#   ./03-web.sh
#
# Idempotentní: konfiguraci nginxu přepíše, statické soubory nechá být. Ty se
# nahrávají z vývojového stroje (build s NEXT_PUBLIC_API_BASE_URL=/).
#
set -euo pipefail

WEB_ROOT="${WEB_ROOT:-/var/www/zasstojime}"
API_PORT="${API_PORT:-8080}"
SITE=/etc/nginx/sites-available/zasstojime

log() { printf '\033[1;34m==>\033[0m %s\n' "$*"; }
warn() { printf '\033[1;33mpozor:\033[0m %s\n' "$*"; }
die() { printf '\033[1;31mchyba:\033[0m %s\n' "$*" >&2; exit 1; }

[[ $EUID -eq 0 ]] || die "spusť jako root"
export LC_ALL=C.UTF-8

# --- 1. nginx ---------------------------------------------------------------
export DEBIAN_FRONTEND=noninteractive
log "instaluji nginx"
apt-get update -qq
apt-get install -y -qq nginx curl >/dev/null

# Statické soubory patří rootovi, nginx (www-data) je jen čte. Ne pod
# /opt/zasstojime: ten má práva 0750 a www-data by do něj neprošel.
mkdir -p "$WEB_ROOT"
chown -R root:root "$WEB_ROOT"
chmod -R u=rwX,go=rX "$WEB_ROOT"

# --- 2. konfigurace ---------------------------------------------------------
log "zapisuji $SITE"
cat > "$SITE" <<EOF
# Spravuje deploy/03-web.sh z repozitáře zasstojime — ruční úpravy přepíše.
server {
    listen 80 default_server;
    server_name _;

    root $WEB_ROOT;
    index index.html;
    # /metodika → /metodika/ relativně, ať přesměrování přežije i proxy před nginxem.
    absolute_redirect off;

    # API serveru. /q/metrics sem nepatří, Prometheus ho stahuje přímo z :$API_PORT.
    location /api/ {
        proxy_pass http://127.0.0.1:$API_PORT;
        proxy_set_header Host \$host;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
    }

    # Soubory s hashem v názvu se nemění.
    location /_next/static/ {
        add_header Cache-Control "public, max-age=31536000, immutable";
        try_files \$uri =404;
    }

    location / {
        add_header Cache-Control "no-cache";
        try_files \$uri \$uri/ =404;
    }

    error_page 404 /404.html;
}
EOF

ln -sf "$SITE" /etc/nginx/sites-enabled/zasstojime
rm -f /etc/nginx/sites-enabled/default
nginx -t -q || die "konfigurace nginxu neprošla (nginx -t)"
systemctl enable -q nginx
if systemctl is-active --quiet nginx; then
  systemctl reload nginx
else
  systemctl start nginx
fi

# --- 3. kontrola ------------------------------------------------------------
if [[ ! -f "$WEB_ROOT/index.html" ]]; then
  log "nginx běží, ale web ještě není nahraný."
  echo
  echo "Z vývojového stroje (v kořeni repozitáře):"
  echo "  (cd web && npm ci && NEXT_PUBLIC_API_BASE_URL=/ npm run build)"
  echo "  rsync -avz --delete web/out/ root@$(hostname -I | awk '{print $1}'):$WEB_ROOT/"
  echo
  echo "Pak spusť tenhle skript znovu, srovná práva a ověří web."
  exit 0
fi

status() { curl -s -o /dev/null -w '%{http_code}' --max-time 5 "http://127.0.0.1$1" || true; }
FAILED=0
for path in / /metodika/ /api/health; do
  code="$(status "$path")"
  if [[ $code == 200 ]]; then
    log "http://127.0.0.1$path → 200"
  else
    warn "http://127.0.0.1$path → ${code:-nic}"
    FAILED=1
  fi
done
(( FAILED == 0 )) || die "web nebo API neodpovídá (journalctl -u nginx, /var/log/nginx/error.log; /api/health vrací 503 před prvním cyklem sběru)"
log "hotovo: http://$(hostname -I | awk '{print $1}')/"
