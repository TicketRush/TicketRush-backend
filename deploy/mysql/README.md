# MySQL 초기 스키마 스냅샷

`init/001-ticket-rush-schema.sql`은 `ticket_rush` 스키마의 DDL 스냅샷이다.
`deploy/docker-compose.prod.yml`이 이 파일을 `/docker-entrypoint-initdb.d`에 마운트해,
prod MySQL이 **최초 기동할 때 1회** 실행한다.

> **스냅샷은 이 파일 하나뿐이다.** 과거 `infra/mysql/init/01-schema.sql`에 사본이 있었으나,
> 어떤 compose도 마운트하지 않는 죽은 파일이라 제거했다(#430). 로컬 `docker-compose.yml`에는
> mysql 서비스가 없다 — 로컬은 `ddl-auto: update`라 init SQL이 필요 없다.

## 왜 필요한가

`prod` 프로파일은 `ddl-auto: validate`인데 스키마를 만들 DDL이 저장소에 없었다.
`SPRING_JPA_HIBERNATE_DDL_AUTO=update`로 덮을 수는 있지만, 그러면 7개 서비스가 동시에
같은 `ticket_rush` 스키마에 DDL을 쳐 경쟁 조건이 생긴다.

이 스냅샷은 **마이그레이션 도구가 아니라 `validate`를 만족시키기 위한 초기 스키마**이므로
[ADR 0003](../../docs/adr/0003-shared-database-with-service-boundaries.md)의 "마이그레이션 도구
미도입" 결정과 충돌하지 않는다.

앱은 `prod`에서 `validate`를 유지한다 — 스키마와 엔티티 불일치를 부팅 시점에 검출하는 안전장치다.

## 언제 재생성하는가

**엔티티(`@Entity`, `@Table`, `@Column`, `@Index`, `@Version`)를 바꿀 때마다.** 바꾸고 재생성하지 않으면
`validate`가 부팅을 거부하며, PR 단계에서 `schema-validate` CI(#408)가 먼저 잡아낸다.

### ⚠️ 이미 가동 중인 DB는 이 스냅샷으로 갱신되지 않는다

init SQL은 **빈 DB의 최초 기동에만** 실행된다. 이미 데이터가 있는 prod DB에는 수동 `ALTER`가 따로 필요하다.
컬럼 추가·타입 변경은 `validate`가 검출하므로 **배포 전에 실행하지 않으면 기동이 실패한다.**
수동 DDL은 관례상 해당 엔티티의 javadoc(예: `Seat`) 또는 ADR(예:
[ADR 0005](../../docs/adr/0005-refund-state-machine-and-recovery.md))에 적어둔다.

## 재생성 절차

> ⚠️ 엔티티 변경을 **먼저** 반영한 뒤 덤프한다. 덤프는 그 시점의 정의를 그대로 굳힌다.

1. 로컬 인프라를 띄운다. 앱 부팅에 Kafka·Redis가 필요하다.

   ```sh
   docker compose up -d redis kafka
   ```

2. 스냅샷 전용 MySQL을 임시로 띄운다. 개발용 `ticket_rush`는 drift 되었을 수 있어 재사용하지 않는다.

   **collation을 prod와 맞춰야 한다** — `deploy/docker-compose.prod.yml`이 `utf8mb4_unicode_ci`로
   기동하는데, 이 옵션 없이 뜨면 MySQL 8 기본값(`utf8mb4_0900_ai_ci`)으로 덤프돼 스냅샷이 어긋난다.

   ```sh
   docker run -d --name mysql-snapshot -p 3307:3306 \
     -e MYSQL_ROOT_PASSWORD=snapshot -e MYSQL_DATABASE=ticket_rush \
     mysql:8.0 \
     --character-set-server=utf8mb4 --collation-server=utf8mb4_unicode_ci
   ```

3. DB를 쓰는 7개 서비스를 **순차** 기동해 각자 자기 테이블을 만든다.
   동시에 띄우면 공유 테이블(outbox·inbox)에서 DDL이 경쟁한다.
   OS 환경변수가 `application-local.yml`보다 우선하므로 접속 대상과 `ddl-auto`를 덮는다.

   ```sh
   for svc in auth-service user-service performance-service seat-service \
              booking-service payment-service ticket-service; do
     SPRING_DATASOURCE_URL=jdbc:mysql://localhost:3307/ticket_rush \
     SPRING_JPA_HIBERNATE_DDL_AUTO=update \
       ./gradlew ":$svc:bootRun"     # "Started ...Application" 로그 확인 후 Ctrl+C
   done
   ```

   (gateway는 datasource가 없어 제외한다.)

4. **낙관적 락 컬럼의 `DEFAULT`를 넣는다.** Hibernate DDL은 `DEFAULT`를 만들지 않지만, 스냅샷의
   `version` 컬럼에는 `DEFAULT '0'`이 있어야 한다 — `version`을 명시하지 않는 시딩 SQL
   (`load-test/seed/seed_load.sql`, `load-tests/fixtures/seat-layouts-120.sql`)이 없으면 `ERROR 1364`로
   깨진다. 이 값은 ADR의 운영 `ALTER`(`... ADD COLUMN version bigint NOT NULL DEFAULT 0`)를 친 prod DB
   형태를 반영한 것이므로, 덤프 전에 임시 DB에도 같은 상태를 만든다.

   ```sh
   docker exec -e MYSQL_PWD=snapshot mysql-snapshot mysql -uroot ticket_rush -e "
     ALTER TABLE booking MODIFY version bigint NOT NULL DEFAULT 0;
     ALTER TABLE seat    MODIFY version bigint NOT NULL DEFAULT 0;"
   ```

   > `@Version` 엔티티를 새로 추가했다면 여기에도 함께 추가한다. 현재 대상은 `Booking`(#397)·`Seat`(#427)뿐이다.

5. 덤프한다. `--skip-comments`는 버전·타임스탬프 헤더를 지운다.
   `sed`는 테이블별 `AUTO_INCREMENT=<n>` 시작값을 지운다 — 덤프를 뜬 DB에 몇 행이 있었는지가
   그대로 굳어 재현이 깨지기 때문이다(컬럼의 `AUTO_INCREMENT` 속성 자체는 남는다).
   `DROP TABLE IF EXISTS`는 **지우지 않는다**(mysqldump 기본) — 현재 스냅샷이 그 형태다.

   ```sh
   docker exec -e MYSQL_PWD=snapshot mysql-snapshot mysqldump -uroot \
     --no-data --skip-comments ticket_rush \
     | sed -E 's/ AUTO_INCREMENT=[0-9]+//g' \
     > deploy/mysql/init/001-ticket-rush-schema.sql
   ```

6. 인덱스·제약·`DEFAULT`가 들어갔는지 확인한다.

   ```sh
   grep -E "idx_seat_performance_id|uk_seat_layout_performance_id" deploy/mysql/init/001-ticket-rush-schema.sql
   grep -c "NOT NULL DEFAULT '0'" deploy/mysql/init/001-ticket-rush-schema.sql   # @Version 엔티티 수와 일치해야 한다
   ```

7. 정리한다.

   ```sh
   docker rm -f mysql-snapshot
   ```

## 검증

PR을 올리면 `.github/workflows/schema-validate.yml`이 이 스냅샷을 빈 MySQL에 적재한 뒤 각 서비스를
`ddl-auto=validate`로 부팅해 drift를 잡는다. 로컬에서 먼저 보려면 같은 일을 수동으로 하면 된다 —
빈 MySQL에 `init/001-ticket-rush-schema.sql`을 적용하고 서비스를 `validate`로 띄운다. 스키마와 엔티티가
어긋나면 `SchemaManagementException`으로 부팅이 실패한다.

> **CI의 한계**: `validate`는 컬럼 존재·타입은 잡지만 **컬럼 길이·인덱스·유니크/FK·nullable·`DEFAULT` 차이는
> 검출하지 않는다.** 인덱스나 `DEFAULT`를 빠뜨린 스냅샷도 CI는 통과시킨다 — 위 6번 확인이 그래서 필요하다.
