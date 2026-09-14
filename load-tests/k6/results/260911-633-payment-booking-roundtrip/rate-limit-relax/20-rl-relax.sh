#!/usr/bin/env bash
# #633 회차용 Rate Limit 일시 완화. 반드시 21-rl-restore.sh 로 원복할 것.
set -uo pipefail
cd ~/ticketrush/deploy || { echo "deploy 디렉토리 없음"; exit 1; }

RUNNING=$(docker inspect gateway-service --format '{{.Config.Image}}' | sed 's/.*://')
ENVTAG=$(grep -E '^IMAGE_TAG=' .env | head -1 | cut -d= -f2-)
echo "running image tag : $RUNNING"
echo ".env IMAGE_TAG    : $ENVTAG"
if [ "$RUNNING" != "$ENVTAG" ]; then
  echo
  echo "ABORT: .env 의 IMAGE_TAG 가 실행 중 태그와 다릅니다."
  echo "       이대로 up 하면 gateway 가 다른 버전으로 교체됩니다. 중단합니다."
  exit 1
fi

BK=.env.bak.$(date -u +%Y%m%d-%H%M%S)
cp .env "$BK"
echo "backup created: $BK"

set_kv() {
  if grep -qE "^$1=" .env; then
    sed -i "s|^$1=.*|$1=$2|" .env
  else
    printf '%s=%s\n' "$1" "$2" >> .env
  fi
}
# confirm: 900/60 = 15 req/s (피크 12/s 커버), burst 1800 = 30건
set_kv RATE_LIMIT_PAYMENT_CONFIRM_REPLENISH_RATE 900
set_kv RATE_LIMIT_PAYMENT_CONFIRM_BURST_CAPACITY 1800
# booking: 1500/60 = 25 req/s (배경부하 20/s 커버), burst 3000 = 50건
set_kv RATE_LIMIT_BOOKING_REPLENISH_RATE 1500
set_kv RATE_LIMIT_BOOKING_BURST_CAPACITY 3000

echo
echo "--- .env 반영값 ---"
grep -E '^RATE_LIMIT_(PAYMENT_CONFIRM|BOOKING)_' .env

echo
echo "--- gateway 만 재생성 (--no-deps) ---"
docker compose -p ticketrush-prod -f docker-compose.prod.yml up -d --force-recreate --no-deps gateway-service
sleep 15

echo
echo "--- 컨테이너에 실제 주입된 값 ---"
docker inspect gateway-service --format '{{range .Config.Env}}{{println .}}{{end}}' | grep -E '^RATE_LIMIT_(PAYMENT_CONFIRM|BOOKING)_'
docker inspect gateway-service --format 'IMAGE={{.Config.Image}}'
echo
echo "완화 적용됨. 회차 종료 후 반드시: bash 21-rl-restore.sh"
