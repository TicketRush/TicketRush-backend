#!/usr/bin/env bash
# Rate Limit 원복. 가장 최근 .env.bak.* 를 되돌린다.
set -uo pipefail
cd ~/ticketrush/deploy || { echo "deploy 디렉토리 없음"; exit 1; }

BK=$(ls -1t .env.bak.* 2>/dev/null | head -1)
if [ -z "$BK" ]; then echo "ABORT: .env.bak.* 백업을 찾을 수 없습니다."; exit 1; fi
echo "restoring from: $BK"
cp "$BK" .env

echo "--- 복원된 .env 의 RATE_LIMIT 항목 ---"
grep -E '^RATE_LIMIT_' .env || echo "(없음 = application.yml 기본값 사용)"

docker compose -p ticketrush-prod -f docker-compose.prod.yml up -d --force-recreate --no-deps gateway-service
sleep 15

echo
echo "--- 컨테이너 주입값 (완화 항목이 사라졌는지) ---"
docker inspect gateway-service --format '{{range .Config.Env}}{{println .}}{{end}}' | grep -E '^RATE_LIMIT_(PAYMENT_CONFIRM|BOOKING)_' || echo "(없음 = 기본값 3/120, 5/120 으로 복귀)"
docker inspect gateway-service --format 'IMAGE={{.Config.Image}}'
