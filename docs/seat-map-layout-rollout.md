# 좌석맵 행·열·배치 크기 전환 배포·롤백 가이드 (#645)

좌석에 1-based 좌표(`seat_row`, `seat_col`)를 저장하고, 좌석맵 응답을 좌석 배열에서 `{ layout, seats }` 객체로 바꾼 변경의 계약·배포·롤백 절차다.
프론트가 `seat_number` 파싱 없이 렌더링하게 하는 것이 목적이다. **응답 형태가 바뀌는 비호환 변경**이다.

이 문서는 절차만 정의한다. 운영 DB 변경, 데이터 수정, 캐시 조작, 실제 배포는 별도 승인을 받은 뒤 수행한다.

> **주의 — `main` 머지 = 자동 배포.** `.github/workflows/cd.yml`은 `main` push에서 전 서비스를 한 번에 교체한다.
> 아래 3장 ①~③(프론트 이중 호환 배포, 확장 DDL, 백필)은 파이프라인에 없으므로 **머지 전에** 끝낸다. 끝나지 않았으면 머지를 보류한다.

## 1. 바뀌는 계약

### 공개 좌석맵 `GET /api/v1/seat/{performanceId}/seat-layouts`

`result`가 배열에서 객체로 바뀐다.

```jsonc
// 이전
"result": [
  { "seat_id": 1, "seat_layout_id": 101, "seat_number": "A-1", "seat_status": "AVAILABLE" }
]

// 이후
"result": {
  "layout": { "total_rows": 10, "max_cols": 12 },
  "seats": [
    { "seat_id": 1, "seat_layout_id": 101, "seat_number": "A-1", "seat_row": 1, "seat_col": 1, "seat_status": "AVAILABLE" },
    { "seat_id": 2, "seat_layout_id": 101, "seat_number": "A-2", "seat_row": 1, "seat_col": 2, "seat_status": "HOLD", "hold_expired_at": "2026-08-01T12:00:00Z" }
  ]
}

// 배치도 미생성(공연 등록 직후 좌석 생성 전) — HTTP 200
"result": { "layout": null, "seats": [] }
```

- `layout`은 공연당 한 번만 싣는다. 좌석마다 크기를 반복하지 않는다.
- `seat_row`는 1..`total_rows`, `seat_col`은 1..`max_cols` 범위다. 공연 안에서 좌표는 중복되지 않는다(DB 유니크 키).
- **`total_rows × max_cols`는 좌석 수가 아니다.** 마지막 행은 부분 행일 수 있다(예: 10,000석 = 26행 × 385열, Z행 375석). 좌석 수는 `seats.length`로 센다.
- 행은 최대 26행이다(고정 26행이 아님). 120석은 10×12, 500석은 25×20이다.
- `seat_number`는 표시용으로 유지한다. 기존 좌석 ID·예매 관계는 바뀌지 않는다.
- `layout: null`은 키를 생략하지 않고 명시한다.

### 관리자 모니터링 `GET /api/v1/seat/admin/{performanceId}/monitoring` (비파괴)

- `result.layout`(`total_rows`, `max_cols`)이 **추가**된다. 기존 `summary`·`seats`는 그대로다.
- `seats[]`에는 공개 API와 같은 `seat_row`·`seat_col`이 붙는다.
- 배치도가 없으면 `layout` 키는 생략된다(전역 NON_NULL).

### 바뀌지 않는 것

- SSE `seat-status/stream` 페이로드(`SeatStatusChangedResponse`) — 좌석 ID로 갱신하므로 좌표를 싣지 않는다.
- `seat-counts`, `numbers`, 관리자 좌석 단건 상세, Kafka 이벤트.

### 좌석맵 캐시

- 키: `seat:seat-map:v2:<performanceId>`(배열) → `seat:seat-map:v3:<performanceId>`(객체). 신버전은 v2를 읽지 않는다.
- v3 키에 `{`로 시작하지 않는 값이 있으면 손상으로 보고 폐기한 뒤 DB로 내려간다.
- TTL 30초, cache-aside, fail-open, 상태 변경 커밋 시 무효화는 그대로다. 미생성 응답(`layout: null` 또는 좌석 없음)은 캐시하지 않는다.

## 2. 프론트 전환 조건

백엔드 배포 **전에** 프론트(좌석맵, `src/api/adminSeatMapper.ts`)가 구/신 응답을 **모두** 처리하는 버전으로 먼저 배포돼야 한다.
롤백하면 배열 응답이 돌아오기 때문에, 이중 호환은 전환이 안정될 때까지 유지한다.

```ts
// 예시 — 구 응답이면 기존 파싱 경로, 신 응답이면 좌표를 그대로 쓴다
const seatMap = Array.isArray(result)
  ? legacyFromSeatNumbers(result)            // 기존 seat_number 파싱
  : { layout: result.layout, seats: result.seats };
if (seatMap.layout === null) {
  renderNotReady(); // 배치도 미생성
} else if (!seatMap.seats.every((s) => s.seat_row != null && s.seat_col != null)) {
  // 좌표가 없는 좌석(백필 전 과도기 데이터, 3장 ③~⑤)이 섞였으면 이 응답은 기존 번호 파싱으로 그린다
  renderSeats(legacyFromSeatNumbers(seatMap.seats));
} else {
  // grid[seat.seat_row - 1][seat.seat_col - 1] = seat, 크기는 layout.total_rows × layout.max_cols
  renderSeats(seatMap);
}
```

- 좌표 `seat_row`·`seat_col`과 크기 `layout`만으로 렌더링하고, `seat_number`는 라벨로만 쓴다.
- **좌석에 좌표 키가 없으면 그 응답은 번호 파싱으로 폴백한다.**
  - 신버전 배포(④)와 잔여 백필(⑤) 사이에 구버전이 만든 좌석은 좌표 없이 내려갈 수 있다.
  - 좌표 없는 응답이 30초 캐시에 실릴 수도 있다.
  - 이 폴백이 없으면 `grid[undefined]`로 렌더링이 깨진다.
- 관리자 화면의 구/신 판별은 `layout` 키가 아니라 `seats[].seat_row` 유무로 한다. 신버전도 배치도가 없으면 `layout` 키를 생략하기 때문이다.
- 백엔드 전환이 끝나고 롤백 가능 기간이 지나면 구 경로와 폴백을 제거한다(프론트 저장소 별도 작업).

## 3. 배포 절차

SQL의 SSOT는 `deploy/mysql/migrations/645-seat-row-col/`이다. 각 파일은 `SeatRowColMigrationSqlTest`가 실제 MySQL 8.0에서 그대로 실행해 검증한다.
모든 단계의 출력은 증적으로 남긴다.

| 단계 | 시점 | 실행 | 통과 조건 |
|---|---|---|---|
| ① 프론트 이중 호환 배포 | 머지 전 | 2장 | 구/신 응답과 좌표 누락 폴백 렌더링 확인 |
| ② 확장 | 머지 전, 구버전 가동 중 | `1-expand.sql` | 아래 **머지 게이트 쿼리**의 `coord_columns = 2` |
| ③ 백필 리포트 → 적용 | 머지 전, 구버전 가동 중 | `2-backfill.sql` (`@mode='report'` → `'apply'`) | report의 `blocked = 0`, apply 후 검증 SELECT 전부 0 |
| ④ 신버전 배포 | `main` 머지 | CD | 전 서비스 교체 완료, 스모크 테스트(4장) |
| ⑤ 잔여 백필 | ④ 직후 | `2-backfill.sql` (`'apply'`) | 검증 SELECT 전부 0 → 좌석맵 캐시 정리 |
| ⑥ 제약 강화 | ⑤ 통과 후 | `3-contract.sql` | `SHOW INDEX FROM seat`에 `uk_seat_performance_id_seat_row_seat_col`, 컬럼 NOT NULL |

### 실행 방법

- 머지 전(②·③)에는 서버에 `deploy/` 번들이 아직 없다. CD가 배포 때만 `~/ticketrush/deploy`에 풀기 때문이다.
- 그래서 **로컬 체크아웃의 파일을 SSH stdin으로 흘린다**(`docs/load-test-guide.md`의 `$SSH` 관용구와 같다).
- 파이프 1회가 MySQL 세션 1개다.

```bash
SSH="ssh -i <key>.pem ubuntu@<EC2_IP>"
MYSQL="docker exec -i ticketrush-mysql sh -c 'mysql -u root -p\"\$MYSQL_ROOT_PASSWORD\" ticket_rush'"
M=deploy/mysql/migrations/645-seat-row-col

# 0) ALTER 전: seat를 붙든 롱 트랜잭션이 없는지 본다(있으면 끝나길 기다린다). ALTER는 lock_wait_timeout 5초로 실패하므로
#    그 경우 원인을 확인하고 재실행한다 — 기다리며 seat 쿼리 전체를 막지 않게 하려는 설정이다.
echo "SELECT trx_id, trx_started, TIMESTAMPDIFF(SECOND, trx_started, NOW()) AS age_s, trx_query
      FROM information_schema.innodb_trx ORDER BY trx_started;" | $SSH "$MYSQL"

# ② 확장
$SSH "$MYSQL" < $M/1-expand.sql

# ③ 리포트 → (blocked = 0 확인) → 적용
printf "SET @mode = 'report';\n" | cat - $M/2-backfill.sql | $SSH "$MYSQL"
printf "SET @mode = 'apply';\n"  | cat - $M/2-backfill.sql | $SSH "$MYSQL"

# ④ 교체 직후·⑤ 뒤: 좌석맵 캐시 네임스페이스만 정리한다(FLUSHALL·seat:lock:* 금지).
# ticketrush-redis 컨테이너의 REDISCLI_AUTH로 인증한다(docs/utc-timestamp-rollout.md 3장 2번과 같은 명령을 $SSH로 감쌌다).
$SSH "docker exec ticketrush-redis sh -c 'redis-cli --scan --pattern \"seat:seat-map:*\" | xargs -r redis-cli unlink'"
$SSH "docker exec ticketrush-redis sh -c 'redis-cli --scan --pattern \"seat:seat-map:*\" | wc -l'"   # 0이어야 한다
```

### 머지 게이트

`main` 머지 = 자동 배포라서 순서를 사람 기억에 맡기지 않는다. PR 머지 직전에 아래 쿼리를 실행하고, 출력을 PR 코멘트로 남긴다.
기대값이 아니면 머지하지 않는다.

- 기대값: `coord_columns = 2`, `null_coord_seats = 0`
- ② 없이 머지하면: 신버전이 `missing column [seat_col]`로 validate 기동에 실패한다. 구 컨테이너가 이미 내려간 뒤라 좌석 서비스 전체 장애가 된다.
- ③ 없이 머지하면: 좌표 없는 좌석맵이 대량으로 나간다.

```bash
echo "SELECT
        (SELECT COUNT(*) FROM information_schema.columns
          WHERE table_schema = 'ticket_rush' AND table_name = 'seat'
            AND column_name IN ('seat_row', 'seat_col')) AS coord_columns,
        (SELECT COUNT(*) FROM seat WHERE seat_row IS NULL OR seat_col IS NULL) AS null_coord_seats;" \
  | $SSH "$MYSQL"
```

- `null_coord_seats`는 게이트 확인과 CD 교체 사이에 구버전이 새 공연을 만들면 0이 아니게 될 수 있다. 그 몫은 ⑤가 채운다.
- 이 창을 없애려면 ③~⑤ 동안 공연 등록을 멈춘다. 그렇지 않으면 2장의 프론트 폴백이 그 사이 좌석을 그린다.

### 백필이 하는 일과 멈추는 경우

- 공연 단위로 두 해석을 검증해 **정확히 하나만 성립할 때만** 채운다.
  - 격자(GRID): `A-1` 형식. 좌석이 행 우선으로 빈칸 없이 채워져 있고 layout 범위 안에 있어야 한다.
  - 순번(SEQ): 부하 시드의 `S-<n>` 형식. n이 1..N으로 연속이고 `total_rows = CEIL(N / max_cols)`여야 한다.
- `S-*`는 실제 S행과 접두사가 겹치므로 정규식만으로 구분하지 않는다.
  - 예를 들어 실제 S행 좌석만 남은 공연은 격자로는 빈칸이 있고, 순번으로는 layout 행 수가 맞지 않아 둘 다 불성립이 된다.
- 다음 경우에는 **어떤 행도 갱신하지 않고** 원인 공연 목록을 출력한 뒤 멈춘다(`blocked > 0`).
  - NULL 좌표를 가진 공연 중 판정 불가(INVALID)나 layout 없음(NO_LAYOUT)이 하나라도 있을 때
  - 이미 채워진 좌표가 판정 좌표와 다를 때(mismatch)
- 멈추면 데이터를 자동으로 삭제하거나 재생성하지 않는다. 원인 공연을 사람이 확인하고, 수동 조치 계획을 별도로 승인받은 뒤 다시 실행한다.
- 멱등이다. 몇 번을 실행해도 NULL 좌표(한쪽만 NULL인 좌석 포함)만 채운다. 한쪽만 채워진 값이 번호와 다르면 덮어쓰지 않고 mismatch로 멈춘다.
  - ⑤는 ③과 ④ 사이에 구버전이 만든 공연(좌표 NULL)을 채우기 위한 재실행이다.
  - 백필 SQL은 캐시를 무효화하지 않는다. 그 사이 적재된 좌표 없는 좌석맵이 최대 30초 남으므로, ⑤ 뒤에 `seat:seat-map:*` 정리를 수행한다("실행 방법"의 캐시 정리 명령).
- ⑤가 `blocked > 0`으로 멈추면 신버전은 이미 가동 중이다. 해당 공연만 좌표 없이 나가고 프론트 폴백이 그린다(2장). ⑥은 원인 조치가 끝날 때까지 보류한다.
- 좌석 행 락을 한 UPDATE로 잡으므로 트래픽이 낮을 때 실행한다. 스냅샷은 READ COMMITTED로 떠서 좌석 선점 쓰기를 막지 않는다.
- 스크립트 끝에서 `@mode`를 지우고 세션 격리 수준을 되돌린다. 같은 세션에서 모드 없이 다시 실행하면 report로 돈다.
- 이미 좌표가 채워진 판정 불가 공연은 목록에만 나오고 번호와 대조하지 않는다. 앱이 만들 수 없는 데이터라서다. 목록에 나오면 사람이 확인한다.

### 순서의 근거

- **확장을 NULL 허용으로 먼저 하는 이유**: 구버전의 좌석 생성 INSERT는 좌표 컬럼을 모른다. NOT NULL이면 ②~④ 사이 공연 등록이 실패한다.
- **백필을 신버전 배포 전에 하는 이유**: 신버전은 좌표를 그대로 응답에 싣는다. 좌표가 NULL이면 `seat_row`·`seat_col` 키가 빠진 좌석이 내려간다.
- **제약 강화를 마지막에 하는 이유**: 구버전이 전부 내려가야 좌표 없는 INSERT가 사라진다. NULL이 남아 있으면 `3-contract.sql` 자체가 실패하므로 누락을 조용히 넘기지 않는다.
- **신버전 엔티티의 좌표는 `updatable = false`다**: ⑤ 도중 신버전이 백필 전에 읽은 좌석을 상태 전이로 flush해도 좌표를 NULL로 덮지 않는다.
- **구·신 버전 동시 서비스는 지원하지 않는다**: 구버전은 v3 캐시를 무효화하지 못해 최대 30초 stale이 생긴다.
  - CD가 전 서비스를 한 번에 교체하는 현재 방식이면 충족된다.
  - 교체 직후 좌석맵 캐시 네임스페이스만 한정해 정리하길 권장한다("실행 방법"의 캐시 정리 명령, `SCAN` + `UNLINK`). `FLUSHALL`과 `seat:lock:*` 정리는 금지다.

## 4. 스모크 테스트

1. 기존 공연 좌석맵: `result.layout`이 해당 `seat_layout`의 `total_rows`·`max_cols`와 같다. `seats.length`가 DB 좌석 수와 같고, 모든 좌석에 `seat_row`·`seat_col`이 있다.
2. 좌표 복원: 격자 공연은 `seat_number` = `(char)('A' + seat_row - 1) + "-" + seat_col`이다.
3. 신규 공연 등록(예: 125석) 직후 조회는 `{"layout": null, "seats": []}` 또는 11행 × 12열이다. 좌석 생성 뒤에는 K행 5석까지 채워져 있다.
4. 캐시: 같은 공연을 두 번 조회하면 두 번째가 히트다(`ticketrush_seat_seatmap_cache_total{result="hit"}`). 좌석 선점 뒤에는 즉시 새 상태가 보인다. Redis에 `seat:seat-map:v3:<id>`만 적재된다.
5. 관리자 모니터링 응답에 `layout`이 있다.

## 5. 롤백 절차

1. 신버전의 트래픽과 쓰기 주체를 멈춘다.
2. ⑥(`3-contract.sql`)을 이미 적용했다면 구버전 기동 **전에** `rollback.sql`을 실행한다.
   - 이 단계를 빠뜨리면 구버전의 좌표 없는 INSERT가 NOT NULL로 실패해 공연 등록 시 좌석이 만들어지지 않는다.
   - 백필 값은 남는다.
3. 구버전을 배포한다. 구버전은 v2 키만 읽으므로 v3 값과 섞이지 않는다. 필요하면 `seat:seat-map:*`을 정리한다.
4. 프론트는 이중 호환 상태이므로 그대로 둔다.
5. **재전환할 때는 3장 ③(리포트 → 적용, `blocked = 0`)부터 머지 전에 다시 수행한다.**
   - 롤백 기간에 구버전이 만든 좌석은 좌표가 NULL이다.
   - ④부터 시작하면 신버전이 그 좌석을 좌표 없이 서비스한다. ⑤가 판정 불가 데이터로 멈추면 그 상태가 조치될 때까지 이어진다.
   - ③이 머지 게이트다. 컬럼이 이미 있으므로 ②는 건너뛴다(다시 실행하면 Duplicate column으로 실패할 뿐 무해하다).
6. 컬럼 자체를 제거하는 것(`rollback.sql` 주석의 DROP)은 좌표 데이터가 사라지므로 별도 승인 사항이다.

## 6. 로컬 개발 DB

로컬은 `ddl-auto: update`(`seat-service/src/main/resources/application-local.yml`)라 운영과 다르게 실패한다.
좌석이 이미 있는 로컬 DB에서 신버전을 그냥 기동하면 다음 순서로 깨질 수 있다(리뷰에서 지적된 경로이며 로컬 재현은 하지 않았다).

1. Hibernate가 좌표 컬럼을 NOT NULL로 추가하고, MySQL이 기존 행을 암묵 기본값 0으로 채운다.
2. 유니크 키 추가는 `(performance_id, 0, 0)` 중복으로 실패한다. 기본 설정에서는 경고 로그만 남고 기동은 계속된다.
3. 좌석맵이 `seat_row: 0`을 내린다.
4. 백필은 이 0을 판정 좌표와 다른 값(mismatch)으로 보고 멈춘다.

- **기동 전(권장):** 로컬 DB에 `1-expand.sql` → `2-backfill.sql`(apply) → `3-contract.sql`을 순서대로 실행하고 신버전을 기동한다. 로컬 데이터가 필요 없으면 DB를 비워 새로 만들어도 된다(`SeatDataInit`이 더미 공연 좌석을 좌표와 함께 다시 만든다).
- **이미 기동해 0이 채워졌다면:** 아래를 실행한 뒤 `2-backfill.sql`(apply) → `3-contract.sql`을 실행한다.
  - 복구 SQL부터 `3-contract.sql`까지는 앱을 기동하지 않는다. 그 사이 기동하면 `ddl-auto: update`가 같은 이름의 유니크 키를 먼저 만든다(NULL은 중복으로 치지 않아 성공한다). 그러면 `3-contract.sql`이 `Duplicate key name`으로 실패한다.
  - 이미 그렇게 됐다면 `3-contract.sql` 전에 `ALTER TABLE seat DROP INDEX uk_seat_performance_id_seat_row_seat_col;`을 실행한다.

  ```sql
  ALTER TABLE seat MODIFY seat_row int NULL, MODIFY seat_col int NULL;
  UPDATE seat SET seat_row = NULL, seat_col = NULL WHERE seat_row = 0 OR seat_col = 0;
  ```
