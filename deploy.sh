#!/bin/bash
set -e

cd /opt/autoservis

echo "==> Git pull (master)"
git checkout master
git pull origin master

echo "==> Build backend"
./mvnw -q clean package -DskipTests

echo "==> Build frontend"
export NVM_DIR="$HOME/.nvm"
[ -s "$NVM_DIR/nvm.sh" ] && \. "$NVM_DIR/nvm.sh"
cd frontend/autoservis-frontend
npm ci
npm run build
cd /opt/autoservis

echo "==> Restart backend service"
sudo systemctl restart autoservis-backend

sleep 3
if systemctl is-active --quiet autoservis-backend; then
    echo "OK: backend bezi"
else
    echo "CHYBA: backend nebezi po restartu, zkontroluj: journalctl -u autoservis-backend -n 50"
    exit 1
fi

echo "==> Hotovo"
