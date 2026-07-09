#!/usr/bin/env bash
# 부하 테스트용 EC2 인스턴스를 중지한다.
#
#   TICKETRUSH_EC2_INSTANCE_ID=i-0123... ./scripts/aws-down.sh
#
# 중지 후에도 EBS 30GB($2.74/월)는 계속 과금된다. 컴퓨트 비용만 멈춘다.
set -euo pipefail

: "${TICKETRUSH_EC2_INSTANCE_ID:?TICKETRUSH_EC2_INSTANCE_ID 환경변수가 필요하다 (예: i-0123456789abcdef0)}"

echo "인스턴스 중지: ${TICKETRUSH_EC2_INSTANCE_ID}" >&2
aws ec2 stop-instances --instance-ids "$TICKETRUSH_EC2_INSTANCE_ID" >/dev/null

echo "stopped 대기 중..." >&2
aws ec2 wait instance-stopped --instance-ids "$TICKETRUSH_EC2_INSTANCE_ID"

echo "중지 완료. 컴퓨트 과금이 멈췄다(EBS는 계속 과금)." >&2
