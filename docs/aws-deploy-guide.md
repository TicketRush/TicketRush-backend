# AWS 배포 가이드 (부하 테스트용 단일 EC2)

[ADR 0004](adr/0004-load-test-execution-topology.md)가 정한 "k6는 로컬, 대상 앱은 AWS 배포본"
토폴로지의 AWS 측 운용 절차다. 부하 테스트 실행 방법 자체는 [`load-test-guide.md`](load-test-guide.md)를 본다.

**이 배포본은 부하 테스트 수치 확보와 데모가 목적이다. 상시 가동하지 않는다.**

## 구성

단일 EC2 + Docker Compose. 컨테이너 13개.

| 계층 | 컨테이너 | 메모리 상한 |
|---|---|---|
| 앱 | gateway · user · auth · performance · booking · payment · seat · ticket | 각 `1g` (실효 힙 768m) |
| 인프라 | mysql `1.5g` · kafka `1.5g` · redis `512m` | |
| 관측 | prometheus `1g` · grafana `512m` | |

상한 합계 13GiB로 16GiB 호스트에 3GiB 여유를 남긴다.

## 인스턴스

**`m7i.xlarge`** (4 vCPU / 16GiB, 서울 리전 $0.2478/hr), EBS 30GB.

`t3` 계열은 쓰지 않는다. 버스터블이라 부하 테스트 중 CPU 크레딧이 소진되면 성능이 계단식으로
떨어진다. 그래프에 찍히는 것이 앱의 성능 한계가 아니라 AWS의 크레딧 소진 곡선이 된다.
월 44시간 기준 `t3.large` 대비 차액은 $6.3다.

## 비용과 안전장치

월 44시간 기준 약 **$14** (컴퓨트 $10.9 + EBS 30GB $2.74 + 공인 IPv4 $0.22).
**켠 채 잊으면 월 $178로 크레딧 전액이 증발한다.**

### 인스턴스를 처음 켜기 전에 설정한다

1. **CloudWatch 자동 중지 알람** — CPU 사용률 < 5%가 1시간 지속 → EC2 `stop` 액션
2. **AWS Budgets 알림** — 월 예산 임계값 도달 시 이메일

둘 다 AWS 콘솔에서 설정한다. 코드로 자동화하지 않는다(계정 단위 설정이라 저장소가 SSOT일 수 없다).

Elastic IP는 쓰지 않는다. 인스턴스 중지 중에도 월 $3.65가 과금되어 on-demand 운용과 충돌한다.
대신 공인 IP가 시작할 때마다 바뀌므로 `scripts/aws-up.sh`가 출력하는 IP를 매번 쓴다.

## 보안 그룹

**본인 공인 IP `/32`만** 허용한다. `0.0.0.0/0`은 어떤 포트에도 열지 않는다.

| 포트 | 용도 |
|---|---|
| 22 | SSH |
| 8080 | gateway |

> ⚠️ `SPRING_PROFILES_ACTIVE=prod,dev`는 DevToken 발급 엔드포인트
> `POST /api/v1/dev/auth/token`을 **인증 없이** 연다(`DevTokenController`는 `@Profile({"local","dev"})`).
> 부하 테스트 write 시나리오에 필요하지만, IP 제한은 선택이 아니라 필수 전제다.
>
> Prometheus(9090)를 열 경우에도 `--web.enable-remote-write-receiver`에 인증이 없어 같은 제한이 적용된다.

## 배포 절차

### 1. 이미지 빌드·푸시 (로컬에서)

EC2에서 Gradle 멀티모듈을 빌드하면 4 vCPU를 오래 점유한다. 로컬에서 빌드해 GHCR로 올린다.

```sh
echo "$GHCR_TOKEN" | docker login ghcr.io -u <github-username> --password-stdin
docker compose -f docker-compose.yml -f docker-compose.aws.yml build
docker compose -f docker-compose.yml -f docker-compose.aws.yml push
```

### 2. EC2에서 기동

```sh
cp .env.aws.example .env.aws   # 값을 채운다. SWAGGER_SERVER_URL의 IP도 갱신
docker compose -f docker-compose.yml -f docker-compose.aws.yml pull
docker compose -f docker-compose.yml -f docker-compose.aws.yml up -d
docker compose -f docker-compose.yml -f docker-compose.aws.yml ps
```

MySQL 컨테이너는 최초 기동 시 `infra/mysql/init/01-schema.sql`을 1회 실행한다.
스키마를 갈아끼우려면 `mysql_data` 볼륨을 지워야 한다 → [`infra/mysql/README.md`](../infra/mysql/README.md)

> ⚠️ `down -v`는 쓰지 마라. 로컬 개발 스택의 redis·kafka·prometheus·grafana 볼륨까지 함께 지운다.
> `docker volume rm ticketrush-backend_mysql_data`로 대상을 한정한다.

앱 8개가 전부 `healthy`가 될 때까지 1~2분 걸린다(`start_period: 90s`).

### 3. 확인

```sh
curl http://<IP>:8080/actuator/health          # {"status":"UP"}
curl -X POST http://<IP>:8080/api/v1/dev/auth/token \
  -H 'Content-Type: application/json' -d '{...}'   # accessToken (prod,dev 조합 검증)
```

### 4. 시작·중지

```sh
export TICKETRUSH_EC2_INSTANCE_ID=i-0123456789abcdef0
./scripts/aws-up.sh      # 공인 IP를 출력한다
./scripts/aws-down.sh    # 테스트가 끝나면 반드시 실행
```

## 알아둘 것

- **CPU 상한 합계는 4.5로 4 vCPU를 넘는다** (앱 8×0.5 + prometheus 0.3 + grafana 0.2).
  `cpus`는 예약이 아니라 상한이라 기동은 되지만, 부하 피크에서 앱끼리 CPU를 다툰다.
- **측정 중에는 Grafana 대시보드를 열어두지 않는다.** 패널마다 도는 주기 쿼리가 측정 대상의
  CPU를 소모한다. 데이터는 TSDB에 쌓이므로 테스트가 끝난 뒤 열어 본다.
- 요청 경로는 여전히 가정용 업로드 회선을 탄다. 초고 VU에서는 업로드 대역이 먼저 상한에 닿는다.
- 컨테이너 단위 리소스 시계열이 필요하면 cAdvisor 추가를 검토한다. 현재 범위 밖이다.
