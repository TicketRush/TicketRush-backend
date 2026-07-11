# 5. 8 GiB 단일 인스턴스에서 컨테이너별 메모리 상한을 적용한다

날짜: 2026-07-12

## 상태

승인됨

## 맥락

TicketRush 배포본은 8 GiB 클래스 EC2 인스턴스(`m7i-flex.large`, 운영체제에서 확인한 실제 메모리 약 7.6 GiB)에 Spring Boot 애플리케이션 8개와 MySQL, Redis, Kafka를 함께 실행한다.

메모리 상한이 없으면 하나의 컨테이너가 호스트 메모리를 과도하게 점유해 다른 서비스까지 함께 종료시킬 수 있다. 특히 여러 JVM과 Kafka, MySQL이 Swap 없는 단일 인스턴스를 공유하므로 컨테이너별 메모리 격리가 필요하다.

다음 대안을 검토했다.

1. **메모리 상한을 적용하지 않는다.**
   - 설정은 단순하지만 단일 컨테이너의 메모리 증가가 호스트 전체 장애로 이어질 수 있어 기각했다.
2. **모든 애플리케이션에 동일한 상한을 적용한다.**
   - 서비스별 사용량 차이를 반영하지 못하고 상대적으로 메모리를 더 사용하는 서비스의 OOM 위험이 있어 기각했다.
3. **실측값을 기준으로 서비스별 차등 상한을 적용한다.**
   - 유휴 상태와 반복 부하 상태에서 사용량을 측정하고 여유 공간을 포함할 수 있어 채택했다.
4. **서비스와 인프라를 여러 인스턴스로 분리한다.**
   - 장애 격리에는 유리하지만 현재 프로젝트 규모에서 추가 비용과 운영 복잡도가 더 커 기각했다.

실측은 `prod` 프로파일과 `ddl-auto=validate`가 적용된 AWS 배포본을 대상으로 수행했다. 로컬의 k6에서 Gateway를 통해 실제 좌석 120개를 반환하는 API를 호출했다.

```text
GET /api/v1/seat/{performanceId}/seat-layouts
```

검증 조건과 결과는 다음과 같다.

- 동시 사용자: 20 VU
- 실행 시간: 회차당 2분
- 반복 횟수: 3회
- 총 요청 수: 7,054건
- 실패 요청 수: 0건
- 회차별 p95: 41.64 ms, 40.82 ms, 40.72 ms
- 호스트 리소스 시계열 샘플: 100개
- 호스트 평균 CPU: 5.93%
- 호스트 최대 CPU: 23.00%
- CloudWatch 1분 지표 최대 CPU: 11.275%
- 호스트 최소 available 메모리: 3,528 MiB
- 애플리케이션 재시작: 0회
- OOMKilled: 없음
- 최종 Actuator 상태: `UP`

## 결정

8 GiB 클래스 단일 EC2 인스턴스에서 다음 `mem_limit`을 적용한다.

| 컨테이너 | 메모리 상한 |
|---|---:|
| gateway-service | 512 MiB |
| user-service | 576 MiB |
| auth-service | 576 MiB |
| performance-service | 576 MiB |
| booking-service | 576 MiB |
| payment-service | 576 MiB |
| seat-service | 640 MiB |
| ticket-service | 576 MiB |
| MySQL | 768 MiB |
| Redis | 128 MiB |
| Kafka | 1,024 MiB |

메모리 상한 합계는 `6,528 MiB`, 즉 `6.375 GiB`다. 인스턴스의 실제 메모리보다 작게 설정해 운영체제, Docker daemon, 커널 및 기타 프로세스가 사용할 공간을 남긴다.

Spring Boot 애플리케이션 8개에는 다음 JVM Heap 설정을 공통 적용한다.

```text
JAVA_TOOL_OPTIONS=-Xms128m -Xmx384m
```

Kafka에는 다음 Heap 설정을 적용한다.

```text
KAFKA_HEAP_OPTS=-Xms512m -Xmx512m
```

좌석 조회 부하에서 가장 높은 사용량을 기록한 `seat-service`에는 다른 애플리케이션보다 큰 640 MiB를 부여한다. 나머지 도메인 서비스는 576 MiB, Gateway는 512 MiB로 제한한다.

## 결과

- 모든 장기 실행 컨테이너에 명시적인 메모리 상한이 적용된다.
- 상한 합계가 인스턴스 메모리보다 작아 호스트 운영 공간을 남긴다.
- 3회 반복 부하에서 모든 컨테이너가 메모리 상한의 85% 미만을 유지했다.
- 가장 높은 사용률은 `seat-service`의 68.10%였다.
- 애플리케이션 8개 모두 `RestartCount=0`, `OOMKilled=false`, `ExitCode=0`을 유지했다.
- 반복 부하 후에도 호스트 available 메모리가 약 3.5 GiB 남았다.
- Gateway Actuator는 최종적으로 `UP`을 반환했다.

부하 중 측정된 최대 사용량은 다음과 같다.

| 컨테이너 | 최대 사용량 | 상한 사용률 |
|---|---:|---:|
| gateway-service | 245.5 MiB | 47.94% |
| user-service | 328.5 MiB | 57.04% |
| auth-service | 322.4 MiB | 55.97% |
| performance-service | 362.2 MiB | 62.88% |
| booking-service | 379.2 MiB | 65.83% |
| payment-service | 350.1 MiB | 60.79% |
| seat-service | 435.9 MiB | 68.10% |
| ticket-service | 356.7 MiB | 61.92% |
| MySQL | 391.8 MiB | 51.02% |
| Redis | 4.1 MiB | 3.20% |
| Kafka | 445.9 MiB | 43.54% |

- 현재 검증은 좌석 배치 읽기 시나리오를 중심으로 수행했다. 쓰기 요청이나 여러 API가 동시에 호출되는 트래픽은 별도 검증이 필요하다.
- 애플리케이션 수, JVM Heap, Kafka 또는 MySQL 설정, EC2 인스턴스 유형이 변경되면 다시 부하 테스트하고 상한을 재평가한다.
- 단일 인스턴스 장애 시 모든 서비스가 함께 영향을 받는 한계는 유지된다.
