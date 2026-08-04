- /etc/nginx/nginx.conf
    - 편입
    - MD5: 2c606adc5af0a25d4503e697611d3578

- /etc/nginx/sites-available/api.ticketrush.store
    - 기존 편입본과 일치
    - MD5: 5ef9b5bfcb2cfa8abde007145744c9ae

- nginx 백업 파일
    - 편입 제외: 일회성 호스트 백업

- Certbot hook
    - 없음

- systemd
    - Snap, Certbot, Amazon SSM 관리 파일만 존재
    - 편입 제외

- Docker daemon
    - 설정 파일 없음
    - 로그 로테이션 미설정은 후속 이슈

- sysctl
    - OS 및 AWS 이미지 관리 파일
    - 편입 제외

- ulimit
    - 커스텀 파일 없음

- cron
    - 패키지 기본 cron만 존재
    - 사용자 crontab 없음

- .env
    - 시크릿 포함으로 편입 제외
    - 키 목록만 .env.prod.example에 반영

- IMAGE_TAG
    - .env와 실행 중 이미지 태그 불일치 발견
    - 검사 스크립트 편입
    - CD 동기화는 후속 fix 이슈