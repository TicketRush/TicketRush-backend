# 7. 관측 스택을 측정 대상과 같은 EC2에 두고, 관측 포트는 SSH 터널로만 연다

날짜: 2026-07-13

## 상태

승인됨

[ADR 0004](0004-load-test-execution-topology.md)가 후속 ADR로 유보한 관측 스택 배치를 결정한다.

## 맥락

[ADR 0004](0004-load-test-execution-topology.md)는 부하 테스트 토폴로지를 "k6는 로컬, 대상 앱은 AWS 배포본"으로 정하면서, k6 클라이언트 지표(`k6_*`)와 앱 계측(`ticketrush_*`, `http_server_requests_seconds`)을 같은 TSDB에 모은다고 결정했다. 다만 **관측 스택을 실제로 어디에 둘지는 "AWS 배포 토폴로지를 정하는 후속 ADR에서 다룬다"며 유보했다.**

그 사이 배포본이 만들어졌다. `deploy/docker-compose.prod.yml`은 애플리케이션 8개와 MySQL·Redis·Kafka를 단일 EC2(`m7i-flex.large`, 2 vCPU / 실측 약 7.6 GiB)에 올린다. **그런데 이 배포본에는 Prometheus도 Grafana도 없다.** 앱 8개는 모두 `/actuator/prometheus`를 노출하지만, 배포본에서 이 지표를 긁는 주체가 없다.

기존 `monitoring/prometheus.yml`은 타깃이 전부 `host.docker.internal:<port>`다. 앱을 호스트에서 직접 실행하는 로컬 개발을 전제한 설정이므로, 앱이 컨테이너로 뜨는 배포본에서는 쓸 수 없다.

배치를 정할 때 작용한 힘은 다음과 같다.

- **Prometheus는 pull 방식이다.** 스크랩 주체가 대상에 접근할 수 있어야 한다.
- **k6의 remote-write는 push다.** 방향이 반대다. 이 비대칭이 아래 결정을 가른다.
- **[ADR 0006](0006-eight-gib-container-memory-limits.md)의 메모리 예산.** 컨테이너 `mem_limit` 합계가 6,528 MiB이고, 인스턴스 실측 메모리는 약 7.6 GiB다.
- **관측 스택이 측정 대상의 리소스를 쓴다.** 부하 테스트 수치를 오염시킬 수 있다.
- **Prometheus의 remote-write receiver와 조회 API에는 인증이 없다.** 포트를 열면 도달 가능한 누구나 TSDB를 읽고 쓴다.
- **TSDB는 MySQL·Kafka와 루트 EBS를 공유한다.** 디스크가 차면 관측 스택이 아니라 서비스 전체가 죽는다.

## 결정

**Prometheus와 Grafana를 측정 대상과 같은 EC2 인스턴스에, 같은 Compose 네트워크(`ticketrush-network`)의 컨테이너로 둔다. 다만 관측 포트는 어느 것도 인터넷에 열지 않는다.**

### 1. 배포본 전용 scrape 설정

`monitoring/prometheus.aws.yml`을 둔다. 앱이 같은 네트워크의 컨테이너이므로 타깃을 **서비스명**으로 적는다. 로컬 개발용 `monitoring/prometheus.yml`은 그대로 둔다. 두 파일은 `scrape_configs` 구조가 같고 타깃 주소만 다르다.

Prometheus 자기 자신(`localhost:9090`)도 스크랩한다. 아래 "전환 시점"이 제시하는 기준(부하 중 Prometheus의 CPU·메모리 점유)을 재려면 `prometheus_*` 자기 지표가 필요하고, 메모리 상한 재평가의 근거도 여기서 나온다.

### 2. actuator를 앱 포트에서 분리한다

**prod 프로파일에서만** `management.server.port: 9091`을 적용한다(각 서비스의 `application-prod.yml`).

분리하지 않으면 **`/actuator/prometheus`가 인터넷에 무인증 공개된다.** gateway의 8080은 인터넷에 열려 있어야 하고(부하 대상), `SecurityConfig`가 `.anyExchange().permitAll()`이며, `/actuator/**`에 매칭되는 게이트웨이 라우트가 없어 요청이 게이트웨이 자신의 actuator 핸들러로 떨어진다. 즉 `http://<EIP>:8080/actuator/prometheus`가 그대로 읽힌다. 여기에는 내부 라우트 ID, 서비스 호스트명, JVM·힙 상태, uri별 요청·에러율, 결제 성공/실패 카운터, DB 커넥션 풀 상태가 담긴다.

9091은 compose에서 publish 하지 않으므로 **컨테이너 네트워크 안의 Prometheus만 접근할 수 있다.** 예외로 gateway의 9091만 `127.0.0.1:9091`에 바인딩한다. CD가 EC2 호스트에서 헬스체크를 해야 하기 때문이며, 루프백이라 인터넷에서는 닿지 않는다.

**`application.yml`이 아니라 `application-prod.yml`에 두는 이유**: 공통 설정에 두면 로컬에서 앱 8개를 호스트에 직접 실행할 때 8개가 9091을 서로 다퉈 기동이 실패한다. prod에만 두면 로컬은 지금까지처럼 앱 포트에서 actuator를 제공하고, `monitoring/prometheus.yml`도 그대로 동작한다.

### 3. 네트워크 노출 — 인터넷에 여는 것은 8080 하나뿐

| 포트 | 대상 | 바인딩 | 접근 방법 |
|---|---|---|---|
| 8080 | gateway (앱) | `0.0.0.0` | 부하 대상. 열려야 한다 |
| 9090 | Prometheus | `127.0.0.1` | **SSH 터널** |
| 3000 | Grafana | `127.0.0.1` | **SSH 터널** |
| 9091 | gateway actuator | `127.0.0.1` | CD 헬스체크(EC2 호스트 내부) |
| 9091 | 나머지 7개 actuator | publish 안 함 | 컨테이너 네트워크 전용 |

```bash
ssh -i <key>.pem -L 3000:localhost:3000 -L 9090:localhost:9090 <user>@<EIP>
```

**Prometheus 9090을 인터넷에 열지 않는다.** k6의 remote-write는 개발 PC에서 EC2로 **나가는 push**이므로, Grafana와 똑같이 SSH 터널로 커버된다. 보안 그룹에 9090 룰을 추가할 필요가 없다.

### 4. TSDB 보존 상한

`--storage.tsdb.retention.size=4GB`, `--storage.tsdb.retention.time=15d`를 적용한다. 상한이 없으면 부하 테스트를 반복할수록 TSDB가 자라고, 루트 EBS가 차는 순간 같은 디스크를 쓰는 MySQL과 Kafka가 함께 죽는다.

### 5. 메모리 상한

ADR 0006의 방식(서비스별 차등)에 따라 Prometheus 384 MiB, Grafana 256 MiB로 정한다. 합계는 6,528 → **7,168 MiB**가 된다.

### 6. 탄력적 IP

**EC2에 탄력적 IP를 할당했다.** 인스턴스를 stop/start해도 공인 주소가 바뀌지 않으므로 k6의 `BASE_URL`과 SSH 접속 주소를 매번 고쳐 쓰지 않아도 된다.

#378의 계획 단계에서는 "탄력적 IP는 인스턴스 중지 중에도 과금되므로 쓰지 않는다"고 적었으나 실제 구성에서는 할당했고, 그 사실이 어떤 문서에도 기록되지 않은 상태였다. 여기에 기록한다. **대가로 인스턴스를 꺼둔 동안에도 탄력적 IP 요금이 발생한다.**

## 결과

### 검토한 대안

**Prometheus를 로컬에 둔다 — 기각.** Prometheus는 pull 방식이라 로컬에서 AWS 앱을 긁으려면 앱 8개의 actuator 포트를 전부 인터넷에 열어야 한다. 이는 ADR 0004가 대안 3을 기각한 **"노출면 악화"와 같은 문제**다. 더해서 스크랩이 15초마다 가정용 회선을 왕복하므로, 회선 지연과 유실이 스크랩 실패(`up=0`)와 지표 결손으로 나타나 측정 자체를 오염시킨다.

**Prometheus 9090을 인터넷에 열고 보안 그룹 `/32`로 막는다 — 기각.** 이 ADR의 초안은 "remote-write 수신을 위해 9090을 열 수밖에 없다"고 적었는데, **이는 틀렸다.** remote-write는 pull이 아니라 **push**다. 개발 PC가 EC2로 나가는 방향이므로 SSH 터널(`-L 9090:localhost:9090`)로 그대로 커버된다. Grafana 3000에 대해 이미 채택한 해법을 9090에도 적용하면 된다.

이 대안을 택했다면 감수했을 것: 인증 없는 receiver와 조회 API가 인터넷에 노출되고(방어선은 콘솔에서 수동 관리하는 보안 그룹 규칙 하나뿐), remote-write 트래픽이 평문 HTTP로 공인망을 지나며, 회선의 공인 IP가 바뀔 때마다 규칙을 갱신해야 하고, `/32`를 실수로 넓히면 누구나 TSDB에 쓸 수 있게 된다. **터널의 비용은 SSH 명령에 `-L` 한 토막을 더하는 것뿐이다.**

**Grafana만 로컬에 둔다 — 성립하지만 기각.** Grafana가 접근하는 대상은 Prometheus 하나뿐이므로 이 대안도 성립하고, 메모리 256 MiB를 아낀다. 그럼에도 기각한 이유는 `datasource.yml`의 url(`http://prometheus:9090`, 서비스명)을 환경변수로 빼는 수정이 필요해져 "provisioning·대시보드 수정 0줄"이라는 이점을 잃고, 아끼는 256 MiB가 실측상 필요하지 않기 때문이다.

**주의: 이 대안은 부하 중 CPU 소모를 줄여주지 않는다.** Grafana를 로컬로 빼도 **쿼리를 실행하는 주체는 여전히 EC2의 Prometheus**다. 이 대안의 이점으로 오해하지 않는다.

**관리형 관측 서비스(Amazon Managed Prometheus/Grafana 등) — 기각.** 비용이 들고 인스턴스처럼 꺼둘 수 없어, ADR 0005·0006이 전제한 on-demand 운용 전략과 구조적으로 충돌한다.

### 얻는 것

- 배포본의 앱 지표가 수집된다. scrape 타깃이 배포 대상과 1:1로 일치하는지는 **CI가 정적으로 대조**한다(`.github/workflows/ci.yml`). CD는 배포 후 `up` 쿼리로 모든 타깃이 실제로 UP인지 확인하고, 아니면 배포를 실패시킨다.
- 로컬 k6의 remote-write 대상을 터널 너머 Prometheus로 돌리면 `k6_*`와 `ticketrush_*`가 **같은 TSDB에 모인다.** `k6_http_req_duration`(생성기가 본 응답시간)과 `http_server_requests_seconds`(앱이 본 처리시간)의 차이가 곧 네트워크 왕복이므로, 가정용 회선이 병목인지 앱이 병목인지 한 화면에서 갈린다. ADR 0004가 의도한 교차 관측이 성립한다.
- Grafana provisioning과 대시보드 JSON 2개를 수정하지 않는다. 로컬 개발 스택도 그대로 동작한다.
- **인터넷에 열리는 포트가 8080 하나로 줄었다.** 이 변경 이전에는 `/actuator/prometheus`가 8080에서 무인증으로 읽혔다.
- TSDB가 named volume(`ticketrush-prometheus-data`)에 남으므로 인스턴스를 껐다 켜도 과거 측정 데이터가 유지된다.

### 감수하는 것

- **관측 스택이 측정 대상의 CPU·메모리를 쓴다.** 스크랩(15초 간격, 타깃 9개)은 부담이 작지만, Grafana 대시보드를 열어두면 패널마다 주기 쿼리가 돌아 Prometheus가 CPU를 소모한다. 2 vCPU 인스턴스에서 이것은 측정값을 흔든다.

  > **부하 테스트 중에는 Grafana 대시보드를 열어두지 않는다.** 데이터는 TSDB에 쌓이므로 **테스트가 끝난 뒤** 열어 본다. (`docs/load-test-guide.md` §7)

- **관측 UI를 보려면 SSH 터널을 띄워야 한다.** 브라우저로 바로 접근하는 것보다 한 단계 번거롭다. 이것이 위 노출면 축소의 대가다.
- **EC2에 SSH로 들어올 수 있는 사람은 Grafana admin과 Prometheus에 접근할 수 있다.** 즉 **SSH 접근권 = 관측 스택 접근권**이다. 더해서 Grafana admin 비밀번호는 `environment:`로 주입하므로 `docker inspect grafana`로 평문 조회된다. `.env`의 `chmod 600`은 여기서 방어가 되지 않는다. 팀의 SSH 키 보유자와 Grafana 접근 허용 대상이 같다는 전제 위에서 이를 감수한다.
- **메모리 여유가 줄어든다.** `mem_limit` 합계가 6,528 → 7,168 MiB가 되어, 실측 메모리 약 7.6 GiB 대비 운영체제·Docker daemon 몫이 약 600 MiB로 좁아진다.
- **TSDB에 4 GB 상한을 두었으므로 오래된 측정 데이터는 지워진다.** 보존해야 할 결과는 `load-tests/k6/results/`에 파일로 남긴다.
- **탄력적 IP 요금이 인스턴스 중지 중에도 발생한다.**
- 단일 인스턴스 장애 시 관측 스택도 함께 죽는다. 인스턴스 자체가 소실되면 그 직전 지표도 잃는다.

### 메모리 재평가 (ADR 0006)

ADR 0006은 "애플리케이션 수, JVM Heap, Kafka 또는 MySQL 설정, EC2 인스턴스 유형이 변경되면 다시 부하 테스트하고 상한을 재평가한다"고 적었다. 컨테이너 2개를 추가하는 이번 변경이 여기 해당한다.

ADR 0006의 부하 실측에서 호스트 available 메모리가 **약 3.5 GiB** 남았고, 컨테이너 최대 사용률은 `seat-service`의 68.10%였다. Prometheus·Grafana가 상한(384 + 256 MiB)을 모두 쓰더라도 available 메모리 안에 들어간다. 따라서 기존 컨테이너의 상한은 **낮추지 않는다.**

> **미검증**: 위 판단은 ADR 0006의 기존 실측(관측 스택이 없던 구성)에 근거한 추정이다. 관측 스택을 포함한 부하 실측은 배포 후 수행하고, 그 결과로 이 절을 갱신한다. 특히 **Prometheus는 부하 테스트 중 k6의 remote-write를 수신하는 컴포넌트라, 상한을 넘기기 가장 쉬운 순간이 정확히 측정이 진행 중인 순간**이다. 부하 중 OOMKill되면 수집하려던 그 데이터를 잃는다. self-scrape로 들어오는 `prometheus_*` 지표로 이를 확인한다.

## 전환 시점

- 부하 테스트 결과가 관측 스택의 리소스 사용에 의해 오염되고 있음이 드러나면(self-scrape 지표에서 부하 중 Prometheus의 CPU 점유가 유의미하게 관측되면), 관측 스택을 별도 인스턴스로 분리한다.
- 관측 데이터를 장기 보존하거나 여러 사람이 상시 열람해야 하면, 관리형 서비스 또는 별도 인스턴스로 옮긴다. 그때는 SSH 터널 전제가 성립하지 않으므로 인증·TLS를 갖춘 노출 방식을 함께 설계한다.
- 실사용자 트래픽이 붙어 관측 스택의 상시 가용성이 요구되면, 측정 대상과 분리한다.
