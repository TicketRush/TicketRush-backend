# MySQL 초기 스키마 스냅샷

`init/01-schema.sql`은 `ticket_rush` 스키마의 DDL 스냅샷이다. 배포 환경의 MySQL이 최초 기동할 때
`/docker-entrypoint-initdb.d`에 마운트해 1회 실행하는 것을 전제로 한다.

> 마운트를 실제로 거는 배포 Compose 구성은 이 저장소에 아직 없다. 별도 이슈에서 다룬다.

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

## 재생성 절차

> ⚠️ 엔티티 변경을 **먼저** 반영한 뒤 덤프한다. 덤프는 그 시점의 `@Index` 정의를 그대로 굳힌다.

1. 로컬 인프라를 띄운다. 앱 부팅에 Kafka·Redis가 필요하다.

   ```sh
   docker compose up -d redis kafka
   ```

2. 스냅샷 전용 MySQL을 임시로 띄운다. 개발용 `ticket_rush`는 drift 되었을 수 있어 재사용하지 않는다.

   ```sh
   docker run -d --name mysql-snapshot -p 3307:3306 \
     -e MYSQL_ROOT_PASSWORD=snapshot -e MYSQL_DATABASE=ticket_rush \
     mysql:8.0
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

4. 덤프한다. `--skip-comments`는 버전·타임스탬프 헤더를 지운다.
   `sed`는 테이블별 `AUTO_INCREMENT=<n>` 시작값을 지운다 — 덤프를 뜬 DB에 몇 행이 있었는지가
   그대로 굳어 재현이 깨지기 때문이다(컬럼의 `AUTO_INCREMENT` 속성 자체는 남는다).

   ```sh
   docker exec -e MYSQL_PWD=snapshot mysql-snapshot mysqldump -uroot \
     --no-data --skip-add-drop-table --skip-comments ticket_rush \
     | sed -E 's/ AUTO_INCREMENT=[0-9]+//g' \
     > infra/mysql/init/01-schema.sql
   ```

5. 인덱스와 제약이 들어갔는지 확인한다.

   ```sh
   grep -E "idx_seat_performance_id|uk_seat_layout_performance_id" infra/mysql/init/01-schema.sql
   ```

6. 정리한다.

   ```sh
   docker rm -f mysql-snapshot
   ```

## 검증

스냅샷이 실제로 `validate`를 통과하는지 보려면, 빈 MySQL에 `init/01-schema.sql`을 적용한 뒤
서비스를 `prod` 프로파일(`ddl-auto: validate`)로 띄운다. 스키마와 엔티티가 어긋나면
`SchemaManagementException`으로 부팅이 실패한다.
