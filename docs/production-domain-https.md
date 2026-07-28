cat > docs/production-domain-https.md <<'EOF'
# 운영 API 도메인 및 HTTPS 운영 가이드

## 1. 개요

TicketRush 운영 Gateway를 EC2 Elastic IP와 8080 포트로 직접 노출하지 않고,
운영 API 도메인을 통해 HTTPS로 제공한다.

운영 API 주소:

```text
https://api.ticketrush.store
```

운영 프론트엔드 도메인:

```text
https://ticketrush.store
```

운영 요청 흐름:

```text
Client
  → api.ticketrush.store:443
  → EC2 Nginx
  → 127.0.0.1:8080
  → gateway-service
```

---

## 2. DNS 설정

가비아 DNS에 다음 A 레코드를 등록한다.

| 유형 | 호스트 | 값 |
|---|---|---|
| A | `api` | `54.116.243.250` |

`54.116.243.250`은 운영 EC2에 연결된 Elastic IP이다.

DNS 확인:

```bash
dig +short A api.ticketrush.store
nslookup api.ticketrush.store
```

공용 DNS Resolver 확인:

```bash
dig +short A api.ticketrush.store @8.8.8.8
dig +short A api.ticketrush.store @1.1.1.1
```

정상 결과:

```text
54.116.243.250
```

---

## 3. EC2 정보

운영 EC2:

```text
Instance ID: i-0a7f8f2c73298dd78
Elastic IP: 54.116.243.250
Private IP: 172.31.9.145
Region: ap-northeast-2
```

Elastic IP는 인스턴스 중지 및 시작 후에도 동일한 주소를 유지하도록 연결한다.

---

## 4. 보안 그룹

운영 EC2 인바운드 규칙:

| 유형 | 포트 | 소스 |
|---|---:|---|
| SSH | 22 | 관리자 현재 공인 IP `/32` |
| HTTP | 80 | `0.0.0.0/0` |
| HTTPS | 443 | `0.0.0.0/0` |

다음 포트는 외부에 공개하지 않는다.

```text
Gateway: 8080
MySQL: 3306
Redis: 6379
Kafka: 9092, 29092
```

현재 공인 IP 확인:

```bash
curl -fsS https://checkip.amazonaws.com
```

SSH 연결 확인:

```bash
nc -vz -w 5 54.116.243.250 22
```

공인 IP가 변경되어 SSH 접속이 실패하면 보안 그룹의 SSH 소스를
새 공인 IP `/32`로 변경한다.

---

## 5. Gateway 포트 제한

운영 Compose에서 Gateway는 EC2 Loopback 주소에만 바인딩한다.

```yaml
gateway-service:
  ports:
    - "127.0.0.1:8080:8080"
```

EC2 내부 확인:

```bash
docker port gateway-service 8080
sudo ss -ltnp | grep ':8080'
```

정상 결과:

```text
127.0.0.1:8080
```

외부 직접 접근 확인:

```bash
curl --connect-timeout 5 \
  http://54.116.243.250:8080/actuator/health
```

연결이 실패하거나 Timeout이 발생해야 정상이다.

Nginx는 EC2 내부의 `127.0.0.1:8080`을 통해서만 Gateway에 접근한다.

---

## 6. Nginx 설치 및 실행

EC2에서 설치한다.

```bash
sudo apt update
sudo apt install -y nginx
sudo systemctl enable --now nginx
```

설정 문법과 상태 확인:

```bash
sudo nginx -t
sudo systemctl status nginx --no-pager
```

정상 기준:

```text
syntax is ok
test is successful
Active: active (running)
```

---

## 7. Nginx Reverse Proxy 설정

사이트 설정 파일:

```text
/etc/nginx/sites-available/api.ticketrush.store
```

기본 Reverse Proxy 구성:

```nginx
server {
    listen 80;
    listen [::]:80;

    server_name api.ticketrush.store;

    location = /actuator/health {
        proxy_pass http://127.0.0.1:8090/actuator/health;
        proxy_http_version 1.1;

        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    location / {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;

        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

> **gzip 지시어가 없는 것은 의도적이다(`#505`).** 응답 압축은 nginx 가 아니라 **각 앱 origin** 에서 건다(현재 seat-service). 앱 8개와 관측 스택이 2 vCPU 한 대에 동거하는 구성이라(ADR 0007) 압축 CPU 를 nginx 한 곳에 몰면 그 자체가 새 병목이 되고, origin 압축은 내부 홉(서비스→게이트웨이) 전송량까지 함께 줄인다. 비교표는 `load-tests/k6/results/260727-348-openrun-e2e/report.md` §3.4 참고. 위 설정은 클라이언트의 `Accept-Encoding` 을 지우지 않으므로(`proxy_set_header Accept-Encoding ""` 없음) 헤더가 상류로 그대로 전달되고, 앱이 붙인 `Content-Encoding: gzip` 도 그대로 내려온다.

설정 활성화:

```bash
sudo ln -sfn \
  /etc/nginx/sites-available/api.ticketrush.store \
  /etc/nginx/sites-enabled/api.ticketrush.store

sudo rm -f /etc/nginx/sites-enabled/default
sudo nginx -t
sudo systemctl reload nginx
```

EC2 내부 Proxy 확인:

```bash
curl -fsS \
  -H 'Host: api.ticketrush.store' \
  http://127.0.0.1/actuator/health \
  && echo
```

정상 결과:

```json
{"groups":["liveness","readiness"],"status":"UP"}
```

---

## 8. TLS 인증서 발급

Certbot은 Snap 패키지를 사용한다.

```bash
sudo snap install core
sudo snap refresh core
sudo snap install --classic certbot
sudo ln -sfn /snap/bin/certbot /usr/bin/certbot
```

인증서 발급 및 Nginx 적용:

```bash
sudo certbot --nginx -d api.ticketrush.store
```

인증서 경로:

```text
/etc/letsencrypt/live/api.ticketrush.store/fullchain.pem
/etc/letsencrypt/live/api.ticketrush.store/privkey.pem
```

인증서 확인:

```bash
sudo certbot certificates
```

Nginx 설정 확인:

```bash
sudo nginx -t
sudo systemctl reload nginx
```

인증서 Private Key의 내용은 터미널, Git 저장소 또는 GitHub Actions 로그에 출력하지 않는다.

---

## 9. HTTP에서 HTTPS로 Redirect

HTTP 요청 확인:

```bash
curl -I http://api.ticketrush.store/actuator/health
```

정상 결과:

```text
HTTP/1.1 301 Moved Permanently
Location: https://api.ticketrush.store/actuator/health
```

`308 Permanent Redirect`도 허용한다.

HTTPS 요청 확인:

```bash
curl -fsS https://api.ticketrush.store/actuator/health && echo
```

정상 결과:

```json
{"groups":["liveness","readiness"],"status":"UP"}
```

HTTPS 응답 헤더 확인:

```bash
curl -I https://api.ticketrush.store/actuator/health
```

정상 기준은 `200 OK`이다.

---

## 10. 인증서 자동 갱신

갱신 모의 테스트:

```bash
sudo certbot renew --dry-run
```

정상 결과:

```text
Congratulations, all simulated renewals succeeded
```

Certbot Timer 확인:

```bash
systemctl list-timers --all |
grep -E 'certbot|snap.certbot'
```

Snap Certbot을 사용하면 다음 Timer가 등록된다.

```text
snap.certbot.renew.timer
```

---

## 11. 운영 환경변수

공개 운영 URL은 `deploy/.env.prod.example`에 기록한다.

```dotenv
SWAGGER_SERVER_URL=https://api.ticketrush.store
CORS_ALLOWED_ORIGIN=https://ticketrush.store
OAUTH_ALLOWED_REDIRECT_DOMAIN=ticketrush.store
```

실제 운영 Secret과 환경변수는 EC2의 다음 파일에 저장한다.

```text
~/ticketrush/deploy/.env
```

파일 권한:

```bash
chmod 600 deploy/.env
stat -c '%a %n' deploy/.env
```

정상 결과:

```text
600 deploy/.env
```

Secret이 포함된 실제 `deploy/.env` 파일은 Git에 커밋하지 않는다.

---

## 12. CORS 설정

Gateway 운영 CORS Origin:

```text
https://ticketrush.store
```

Preflight 요청 검증:

```bash
curl \
  --silent \
  --show-error \
  --dump-header - \
  --output /dev/null \
  -X OPTIONS \
  https://api.ticketrush.store/api/v1/auth/signup/email-verification/send \
  -H 'Origin: https://ticketrush.store' \
  -H 'Access-Control-Request-Method: POST' \
  -H 'Access-Control-Request-Headers: Content-Type,Authorization'
```

정상 응답에는 다음 헤더가 포함되어야 한다.

```text
Access-Control-Allow-Origin: https://ticketrush.store
Access-Control-Allow-Credentials: true
```

---

## 13. OAuth Redirect URI

현재 소셜 로그인 API 구조:

```text
GET  /api/v1/auth/oauth/{provider}/url
POST /api/v1/auth/social/login
```

현재 백엔드에는 OAuth 공급자가 직접 호출할 다음 Callback API가 없다.

```text
GET /api/v1/auth/kakao/callback
GET /login/oauth2/code/google
GET /api/v1/auth/naver/callback
```

따라서 OAuth 공급자는 프론트엔드 Callback 페이지로 인가 코드를 전달하고,
프론트엔드는 받은 `provider`와 `code`를 다음 API로 전달해야 한다.

```text
POST /api/v1/auth/social/login
```

프론트엔드 Callback 경로가 확정되기 전까지 운영 Redirect URI는 비워둔다.

```dotenv
KAKAO_REDIRECT_URI=
GOOGLE_REDIRECT_URI=
NAVER_REDIRECT_URI=
```

다음 세 값은 반드시 동일해야 한다.

```text
프론트엔드의 실제 Callback URL
백엔드 운영 환경변수의 Redirect URI
OAuth 공급자 콘솔에 등록한 Redirect URI
```

OAuth Callback 경로 확정 및 프론트엔드 API Base URL 변경은
프론트엔드 담당 작업과 연계한다.

운영 API Base URL:

```text
https://api.ticketrush.store
```

---

## 14. CD 배포 후 검증

CD Workflow는 컨테이너 안정화 확인 후 두 단계의 Health Check를 수행한다.

### 내부 Gateway Health Check

```bash
curl -fsS http://127.0.0.1:8090/actuator/health
```

Gateway가 EC2 내부에서 정상적으로 응답하는지 확인한다.

### 외부 HTTPS Health Check

```bash
curl -fsS https://api.ticketrush.store/actuator/health
```

외부 검사는 다음 경로를 함께 확인한다.

```text
DNS
→ EC2 보안 그룹
→ TLS 인증서
→ Nginx
→ Gateway
```

내부 및 외부 Health Check가 모두 성공한 이후에만
`deploy/CURRENT_RELEASE`에 새 이미지 태그를 기록한다.

외부 HTTPS 요청이 실패하면 Workflow 로그에 다음 장애 범위를 표시한다.

```text
DNS
TLS certificate
Nginx
Public network reachability
```

---

## 15. 운영 상태 확인

애플리케이션 컨테이너 확인:

```bash
docker compose \
  --env-file deploy/.env \
  -f deploy/docker-compose.prod.yml \
  ps --all
```

Gateway 상태 확인:

```bash
docker inspect gateway-service \
  --format 'Running={{.State.Running}} OOMKilled={{.State.OOMKilled}} RestartCount={{.RestartCount}} ExitCode={{.State.ExitCode}}'
```

정상 기준:

```text
Running=true
OOMKilled=false
RestartCount=0
ExitCode=0
```

Gateway 내부 Health:

```bash
curl -fsS http://127.0.0.1:8090/actuator/health && echo
```

외부 HTTPS Health:

```bash
curl -fsS https://api.ticketrush.store/actuator/health && echo
```

---

## 16. 장애 대응

### Nginx 상태

```bash
sudo systemctl status nginx --no-pager
sudo nginx -t
sudo journalctl -u nginx --since '30 minutes ago' --no-pager
```

### Nginx 로그

```bash
sudo tail -100 /var/log/nginx/access.log
sudo tail -100 /var/log/nginx/error.log
```

### Gateway 로그

```bash
docker logs --since 10m gateway-service
```

### DNS 확인

```bash
dig +short A api.ticketrush.store
dig +short A api.ticketrush.store @8.8.8.8
dig +short A api.ticketrush.store @1.1.1.1
```

### 인증서 확인

```bash
sudo certbot certificates
sudo certbot renew --dry-run
```

장애 범위 구분:

```text
내부 Health 실패
→ Gateway 또는 애플리케이션 장애

내부 Health 성공 + 외부 HTTPS 실패
→ DNS, 보안 그룹, Nginx, TLS 또는 외부 네트워크 장애
```

---

## 17. 보안 주의사항

다음 정보는 Git 저장소나 GitHub Actions 로그에 출력하지 않는다.

```text
인증서 Private Key
JWT Secret
OAuth Client Secret
DB Password
Internal API Token
Gateway Internal Token
Toss Payments Secret
Slack Webhook URL
```

다음 파일과 내용은 커밋하지 않는다.

```text
deploy/.env
/etc/letsencrypt/live/api.ticketrush.store/privkey.pem
```

공개 도메인, 인증서 경로, Nginx 설정 경로는 문서화할 수 있지만
Secret과 Private Key의 실제 내용은 기록하지 않는다.
