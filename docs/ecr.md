# AWS ECR 연동

## Repository naming convention

ECR Repository는 서비스별로 분리한다.

- ticketrush/auth-service
- ticketrush/user-service
- ticketrush/booking-service
- ticketrush/gateway-service
- ticketrush/payment-service
- ticketrush/performance-service
- ticketrush/seat-service
- ticketrush/ticket-service

## Region

- ap-northeast-2

## 검증 내용

- AWS CLI에서 TicketRush IAM 사용자 인증 확인
- Docker ECR login 확인
- auth-service 기준 Docker build/tag/push 검증 완료

## auth-service push 검증 명령어

```bash
docker build \
  --platform linux/amd64 \
  --build-arg SERVICE=auth-service \
  -t ticketrush/auth-service:latest .

docker tag ticketrush/auth-service:latest \
079209844823.dkr.ecr.ap-northeast-2.amazonaws.com/ticketrush/auth-service:latest

docker push \
079209844823.dkr.ecr.ap-northeast-2.amazonaws.com/ticketrush/auth-service:latest