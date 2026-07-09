# MySQL 초기 스키마 스냅샷

`init/01-schema.sql`은 `docker-compose.aws.yml`의 MySQL 컨테이너가 최초 기동할 때
`/docker-entrypoint-initdb.d`에서 1회 실행하는 초기 스키마다.

## 왜 필요한가

`prod` 프로파일은 `ddl-auto: validate`인데 스키마를 만들 DDL이 저장소에 없었다.
`SPRING_JPA_HIBERNATE_DDL_AUTO=update`로 덮을 수는 있지만, 그러면 7개 서비스가 동시에
같은 `ticket_rush` 스키마에 DDL을 쳐 경쟁 조건이 생긴다.

이 스냅샷은 **마이그레이션 도구가 아니라 `validate`를 만족시키기 위한 초기 스키마**이므로
[ADR 0003](../../docs/adr/0003-shared-database-with-service-boundaries.md)의 "마이그레이션 도구
미도입" 결정과 충돌하지 않는다.

앱은 `prod`에서 `validate`를 유지한다 — 스키마와 엔티티 불일치를 부팅 시점에 검출하는 안전장치다.

## 언제 재생성하는가

**엔티티(`@Entity`, `@Table`, `@Column`, `@Index`)를 바꿀 때마다.** 바꾸고 재생성하지 않으면
`validate`가 부팅을 거부한다.

`init/`은 **볼륨이 비어 있을 때만** 실행된다. 갈아끼우려면 `mysql_data` 볼륨을 지워야 한다.

> ⚠️ `docker compose ... down -v`를 쓰지 마라. `mysql_data`뿐 아니라 로컬 개발 스택의
> `redis_data`·`kafka-data`·`prometheus_data`·`grafana_data`까지 함께 지운다.
> 아래처럼 `mysql_data`만 지정해 지운다.

## 재생성 절차

> ⚠️ 엔티티 변경을 **먼저** 반영해 이미지를 빌드한 뒤 덤프한다.
> 덤프는 그 시점의 `@Index` 정의를 그대로 굳힌다.

편의상 아래 별칭을 쓴다.

```sh
alias dc='docker compose -f docker-compose.yml -f docker-compose.aws.yml'
```

1. MySQL 볼륨만 비우고 인프라를 띄운다. 볼륨이 비어야 다음 기동에서 `init/`이 다시 실행된다.

   ```sh
   dc down
   docker volume rm ticketrush-backend_mysql_data   # 볼륨명 = {compose 프로젝트명}_mysql_data
   dc up -d mysql kafka redis
   ```

2. DB를 쓰는 7개 서비스를 `ddl-auto: update`로 **순차** 기동한다. 각자 자기 테이블을 만든다.
   동시에 띄우면 공유 테이블(outbox·inbox)에서 DDL이 경쟁한다.
   OS 환경변수가 `application-prod.yml`보다 우선하므로 `validate`를 덮는다.

   ```sh
   for svc in auth-service user-service performance-service seat-service \
              booking-service payment-service ticket-service; do
     SPRING_JPA_HIBERNATE_DDL_AUTO=update dc up -d "$svc"
     dc logs -f "$svc" | grep -m1 "Started .*Application"   # 부팅 완료까지 대기
   done
   ```

3. 덤프한다. `--skip-comments`는 버전·타임스탬프 헤더를 지워 재생성 시 diff를 깨끗하게 만든다.

   ```sh
   dc exec -T mysql mysqldump -uroot -p"$MYSQL_ROOT_PASSWORD" \
     --no-data --skip-add-drop-table --skip-comments ticket_rush \
     > infra/mysql/init/01-schema.sql
   ```

4. `idx_seat_performance_id`와 `uk_seat_layout_performance_id`가 파일에 있는지 확인한다.

   ```sh
   grep -E "idx_seat_performance_id|uk_seat_layout_performance_id" infra/mysql/init/01-schema.sql
   ```

5. 스냅샷이 실제로 `validate`를 통과하는지 검증한다. MySQL 볼륨을 비우고 전체를 다시 띄운다.

   ```sh
   dc down
   docker volume rm ticketrush-backend_mysql_data
   dc up -d          # init/01-schema.sql 이 1회 실행된다
   dc ps             # 13개 컨테이너가 전부 healthy
   ```

   스키마와 엔티티가 어긋나면 여기서 `SchemaManagementException`으로 부팅이 실패한다.
