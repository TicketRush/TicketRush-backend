-- #645 ② 백필: 기존 좌석의 seat_number를 읽어 seat_row / seat_col을 채운다.
--
-- 사용법 (순서·권한·배포 연계는 docs/seat-map-layout-rollout.md가 SSOT):
--   SET @mode = 'report';  -- 기본값. 판정·리포트만 하고 아무 행도 바꾸지 않는다.
--   SET @mode = 'apply';   -- 리포트의 blocked = 0일 때만 NULL 좌표를 채운다.
--   그 다음 이 파일을 같은 세션에서 실행한다. 여러 번 실행해도 안전하다(NULL 행만 채운다).
--
-- 기존 좌석·예매는 삭제하거나 재생성하지 않는다. seat_id·booking 관계는 그대로이고 좌표 두 컬럼만 바뀐다.
--
-- ============================================================================
-- 왜 정규식만으로 채우지 않는가
-- ============================================================================
-- 좌석 번호는 두 출처가 섞여 있다.
--   GRID : 생성 유스케이스·격자 시드가 만든 '<행 문자>-<열>'(A-1..). SeatCreateDefaultLayoutUseCase가 행 우선으로
--          좌석 수만큼 채우므로 마지막 행만 부분 행이다.
--   SEQ  : 부하 시드(seed_seat_counts.sql 등)가 만든 'S-<n>' 단조 증가. 행 문자가 아니라 고정 접두사다.
-- 'S-12'는 두 문법에 모두 맞는다(실제 S행 = 19행 12열 / 시드 12번째 좌석). 그래서 좌석 한 건이 아니라
-- **공연 단위**로 두 해석이 각각 성립하는지 layout 범위·중복·연속성까지 검증한다.
--
--   GRID 성립: 전 좌석이 격자 문법이고, row <= total_rows, col <= max_cols이며,
--              위치 (row-1)*max_cols + col의 집합이 정확히 {1..N}이다(중복·빈칸 없음 = 행 우선 채움 출처).
--   SEQ  성립: 전 좌석이 'S-<n>'이고, n의 집합이 정확히 {1..N}이며, total_rows = CEIL(N/max_cols)다.
--              시드는 layout을 항상 이 식으로 만든다(seed_seat_counts.sql 등). 부등호가 아니라 등호인 이유: 실제 S행
--              좌석만 남은 공연('S-1'..'S-12', layout 20행)도 번호 집합은 {1..12}라, 부등호면 시드로 오판해 19행을
--              1행으로 채운다. 실제 격자에서 S행이 쓰이려면 total_rows >= 19여야 하는데 S행 한 줄은 max_cols석 이하라
--              CEIL(N/max_cols) = 1이 되어 등호가 성립할 수 없다.
--
-- 두 해석은 동시에 성립할 수 없다. GRID가 성립하면 위치 1, 즉 'A-1'이 반드시 있으므로 전 좌석이 'S-'일 수 없다.
-- 그래서 모호함은 "둘 다 성립"이 아니라 "둘 다 불성립"(INVALID)으로만 나타난다. 예: 실제 S행 좌석만 남은 공연,
-- 빈칸이 있는 공연, 소문자 번호, layout이 없거나 다른 공연을 가리키는 좌석. 이런 공연이 하나라도 NULL 좌표를 가지면
-- **어떤 행도 갱신하지 않고** 멈춘다. 이미 채워진 좌표가 판정 좌표와 다를 때(mismatch)도 멈춘다.
--
-- ponytail: 단일 UPDATE라 대상 좌석 행 락이 한 트랜잭션에 묶인다. 트래픽이 낮을 때 실행한다. 락 시간이 문제가 되면
-- 공연 범위로 나눈 변형이 필요한데, 판정·차단(@blocked)은 전역으로 유지해야 하므로 이 파일을 복사해 고치지 말고
-- 범위 파라미터를 추가하는 변경으로 다룬다(SeatRowColMigrationSqlTest가 이 파일 원본만 검증한다).
--
-- 세션 상태: 끝에서 @mode를 지우고 격리 수준을 되돌린다. 대화형 세션에서 apply 후 다시 실행해도 기본값 report로 돈다.

-- INSERT ... SELECT가 REPEATABLE READ에서는 원본 seat 행에 공유 next-key 락을 건다. READ COMMITTED면
-- 일관된 읽기로 스냅샷을 떠 좌석 선점 쓰기를 막지 않는다.
SET @prev_isolation_645 = @@SESSION.transaction_isolation;
SET SESSION TRANSACTION ISOLATION LEVEL READ COMMITTED;
SET @mode = IFNULL(@mode, 'report');

-- ---- 1) 좌석별 두 해석 ------------------------------------------------------
-- REGEXP_LIKE의 'c'는 대소문자 구분이다. 컬럼 콜레이션(utf8mb4_unicode_ci)을 따르면 'a-1'도 격자로 읽힌다.
-- 자릿수 상한은 int 범위 안에서 CAST가 넘치지 않게 둔 것이다(seat_number는 varchar(10)).
DROP TEMPORARY TABLE IF EXISTS tmp_645_seat;
CREATE TEMPORARY TABLE tmp_645_seat (
  seat_id        bigint NOT NULL PRIMARY KEY,
  performance_id bigint NOT NULL,
  seat_row       int NULL,
  seat_col       int NULL,
  total_rows     int NULL,
  max_cols       int NULL,
  g_row          int NULL,
  g_col          int NULL,
  seq            int NULL,
  KEY idx_tmp_645_seat_performance_id (performance_id)
);

INSERT INTO tmp_645_seat
SELECT s.seat_id,
       s.performance_id,
       s.seat_row,
       s.seat_col,
       sl.total_rows,
       sl.max_cols,
       IF(REGEXP_LIKE(s.seat_number, '^[A-Z]-[1-9][0-9]{0,7}$', 'c'), ASCII(s.seat_number) - 64, NULL),
       IF(REGEXP_LIKE(s.seat_number, '^[A-Z]-[1-9][0-9]{0,7}$', 'c'),
          CAST(SUBSTRING(s.seat_number, 3) AS UNSIGNED), NULL),
       IF(REGEXP_LIKE(s.seat_number, '^S-[1-9][0-9]{0,7}$', 'c'),
          CAST(SUBSTRING(s.seat_number, 3) AS UNSIGNED), NULL)
FROM seat s
-- performance_id까지 맞춰 조인한다. 다른 공연의 layout을 가리키는 좌석은 layout 없음으로 떨어진다.
LEFT JOIN seat_layout sl
  ON sl.seat_layout_id = s.seat_layout_id
 AND sl.performance_id = s.performance_id;

-- ---- 2) 공연별 판정 --------------------------------------------------------
-- 집계 비교가 NULL을 만나면 결과가 NULL이 되므로 COALESCE로 불성립(0)에 묶는다.
DROP TEMPORARY TABLE IF EXISTS tmp_645_perf;
CREATE TEMPORARY TABLE tmp_645_perf AS
SELECT performance_id,
       COUNT(*)                                   AS seat_count,
       SUM(seat_row IS NULL OR seat_col IS NULL)  AS null_coord_seats,
       MIN(total_rows)                            AS total_rows,
       MIN(max_cols)                              AS max_cols,
       COALESCE(COUNT(total_rows) = COUNT(*), 0)  AS layout_ok,
       COALESCE(
         COUNT(g_row) = COUNT(*)
         AND MAX(g_row) <= MIN(total_rows)
         AND MAX(g_col) <= MIN(max_cols)
         AND COUNT(DISTINCT (g_row - 1) * max_cols + g_col) = COUNT(*)
         AND MAX((g_row - 1) * max_cols + g_col) = COUNT(*), 0) AS grid_ok,
       COALESCE(
         COUNT(seq) = COUNT(*)
         AND COUNT(DISTINCT seq) = COUNT(*)
         AND MAX(seq) = COUNT(*)
         AND CEIL(COUNT(*) / MIN(max_cols)) = MIN(total_rows), 0) AS seq_ok,
       CAST(NULL AS CHAR(10))                     AS kind
FROM tmp_645_seat
GROUP BY performance_id;

UPDATE tmp_645_perf
SET kind = CASE
             WHEN layout_ok = 0 THEN 'NO_LAYOUT'
             WHEN grid_ok = 1   THEN 'GRID'
             WHEN seq_ok = 1    THEN 'SEQ'
             ELSE 'INVALID'
           END;

-- ---- 3) 리포트 --------------------------------------------------------------
-- mismatch: 이미 채워진 좌표가 판정 좌표와 다른 좌석 수(GRID/SEQ 공연만). 신규 생성·갱신된 시드의 정합성 검증을 겸한다.
-- 한쪽만 채워진 좌석도 채워진 쪽이 판정과 다르면 센다 — 틀린 값을 아래 UPDATE가 조용히 덮어쓰지 않게 한다.
-- NULL인 쪽은 비교에서 빼고(IS NULL OR =), 채워진 쪽만 본다.
SELECT COUNT(*) INTO @mismatch
FROM (
  SELECT t.seat_row,
         t.seat_col,
         IF(p.kind = 'GRID', t.g_row, CEIL(t.seq / t.max_cols))          AS expected_row,
         IF(p.kind = 'GRID', t.g_col, MOD(t.seq - 1, t.max_cols) + 1)    AS expected_col
  FROM tmp_645_seat t
  JOIN tmp_645_perf p ON p.performance_id = t.performance_id
  WHERE p.kind IN ('GRID', 'SEQ')
    AND (t.seat_row IS NOT NULL OR t.seat_col IS NOT NULL)
) c
WHERE NOT ((c.seat_row IS NULL OR c.seat_row = c.expected_row)
       AND (c.seat_col IS NULL OR c.seat_col = c.expected_col));

SELECT COUNT(*) INTO @invalid_with_nulls
FROM tmp_645_perf
WHERE kind IN ('NO_LAYOUT', 'INVALID') AND null_coord_seats > 0;

SET @blocked = @invalid_with_nulls + @mismatch;

SELECT @mode AS mode_used,
       @blocked AS blocked,
       @invalid_with_nulls AS invalid_performances_with_null_coords,
       @mismatch AS mismatched_seats;

SELECT kind,
       COUNT(*)              AS performances,
       SUM(seat_count)       AS seats,
       SUM(null_coord_seats) AS null_coord_seats
FROM tmp_645_perf
GROUP BY kind
ORDER BY kind;

-- 멈춘 원인 목록. 좌표가 이미 다 채워진 INVALID 공연도 사람이 볼 수 있게 함께 싣는다(갱신 대상은 아니다).
SELECT performance_id, kind, seat_count, null_coord_seats, total_rows, max_cols
FROM tmp_645_perf
WHERE kind IN ('NO_LAYOUT', 'INVALID')
ORDER BY null_coord_seats DESC, performance_id
LIMIT 200;

-- ---- 4) 적용 ---------------------------------------------------------------
-- @mode = 'apply' 이고 @blocked = 0 일 때만 행이 바뀐다. 스냅샷 뒤에 생긴 좌석은 조인되지 않아 NULL로 남으며
-- 다음 실행이 채운다.
UPDATE seat s
JOIN tmp_645_seat t ON t.seat_id = s.seat_id
JOIN tmp_645_perf p ON p.performance_id = t.performance_id
SET s.seat_row = IF(p.kind = 'GRID', t.g_row, CEIL(t.seq / t.max_cols)),
    s.seat_col = IF(p.kind = 'GRID', t.g_col, MOD(t.seq - 1, t.max_cols) + 1)
WHERE (s.seat_row IS NULL OR s.seat_col IS NULL)
  AND p.kind IN ('GRID', 'SEQ')
  AND @mode = 'apply'
  AND @blocked = 0;

SET @updated_seats = ROW_COUNT();
SELECT @updated_seats AS updated_seats;

-- ---- 5) 검증 (3-contract.sql 전에 전부 0이어야 한다) ------------------------
SELECT
  (SELECT COUNT(*) FROM seat WHERE seat_row IS NULL OR seat_col IS NULL) AS null_coord_seats,
  (SELECT COUNT(*) FROM (
     SELECT 1 FROM seat
     WHERE seat_row IS NOT NULL AND seat_col IS NOT NULL
     GROUP BY performance_id, seat_row, seat_col
     HAVING COUNT(*) > 1) d) AS duplicate_coords,
  (SELECT COUNT(*)
   FROM seat s
   LEFT JOIN seat_layout sl
     ON sl.seat_layout_id = s.seat_layout_id AND sl.performance_id = s.performance_id
   WHERE s.seat_row IS NOT NULL
     AND (sl.seat_layout_id IS NULL
          OR s.seat_row < 1 OR s.seat_col < 1
          OR s.seat_row > sl.total_rows OR s.seat_col > sl.max_cols)) AS out_of_layout_seats;

DROP TEMPORARY TABLE IF EXISTS tmp_645_seat;
DROP TEMPORARY TABLE IF EXISTS tmp_645_perf;
SET SESSION transaction_isolation = @prev_isolation_645;
SET @mode = NULL;
