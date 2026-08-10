#!/bin/bash
set -e

echo "==> Instaluji systemd service"
sudo cp /opt/autoservis/deploy/autoservis-backend.service /etc/systemd/system/autoservis-backend.service
sudo systemctl daemon-reload

echo "==> Instaluji sudoers pravidlo (jen restart/start/stop/status této služby)"
sudo cp /opt/autoservis/deploy/autoservis-sudoers /etc/sudoers.d/autoservis-deploy
sudo chmod 440 /etc/sudoers.d/autoservis-deploy
sudo visudo -c

echo "==> Ukoncuji rucne spusteny java proces (pokud bezi)"
pkill -f 'target/autoservis-0.0.1-SNAPSHOT.jar' || true
sleep 2

echo "==> Startuji a povoluji systemd sluzbu"
sudo systemctl enable autoservis-backend
sudo systemctl start autoservis-backend

sleep 3
sudo systemctl status autoservis-backend --no-pager
