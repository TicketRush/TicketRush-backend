#!/usr/bin/env bash
# 부하 테스트용 EC2 인스턴스를 켜고 공인 IP를 출력한다.
#
#   TICKETRUSH_EC2_INSTANCE_ID=i-0123... ./scripts/aws-up.sh
#
# 공인 IP는 중지·시작 때마다 바뀐다. Elastic IP는 인스턴스 중지 중에도 월 $3.65가 과금되어
# on-demand 운용과 맞지 않으므로 쓰지 않는다. 매번 출력된 IP를 BASE_URL로 넘긴다.
#
# 끝나면 반드시 ./scripts/aws-down.sh 로 중지한다. 켠 채 잊으면 월 $178다.
set -euo pipefail

: "${TICKETRUSH_EC2_INSTANCE_ID:?TICKETRUSH_EC2_INSTANCE_ID 환경변수가 필요하다 (예: i-0123456789abcdef0)}"

echo "인스턴스 시작: ${TICKETRUSH_EC2_INSTANCE_ID}" >&2
aws ec2 start-instances --instance-ids "$TICKETRUSH_EC2_INSTANCE_ID" >/dev/null

echo "running 대기 중..." >&2
aws ec2 wait instance-running --instance-ids "$TICKETRUSH_EC2_INSTANCE_ID"

ip=$(aws ec2 describe-instances \
  --instance-ids "$TICKETRUSH_EC2_INSTANCE_ID" \
  --query 'Reservations[0].Instances[0].PublicIpAddress' \
  --output text)

cat >&2 <<EOF

공인 IP: ${ip}

  헬스체크:   curl http://${ip}:8080/actuator/health
  부하 테스트: docker compose --profile loadtest run --rm k6 \\
                 run -e BASE_URL=http://${ip}:8080 -e PERF_ID=1 /scripts/scenarios/seat-layouts.js

  ※ 앱 컨테이너가 전부 healthy가 될 때까지 1~2분 걸린다.
EOF

# stdout에는 IP만 흘려 다른 명령이 파이프로 받을 수 있게 한다.
echo "$ip"
