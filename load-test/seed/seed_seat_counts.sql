-- ============================================================================
-- #403 좌석 상태 집계 · SSE 구독 코호트 시딩 (MySQL 8.0+ / 스키마 ticket_rush)
--
--   /seat-counts 는 `WHERE performance_id = ?` 하나로 그 공연의 좌석 전 행을 훑어
--   CASE WHEN 다중 COUNT 를 계산한다(SeatRepository.java:18-32). 즉 응답 시간이 공연당
--   좌석 수에 정비례하므로, 좌석 수와 상태 분포를 고정하지 않으면 측정값을 해석할 수 없다.
--   이 파일은 그 전제를 만들기 위해 '크기와 상태 분포를 지정한 공연 1건' 을 만든다.
--
--   ⚠ 기존 LOADTEST 코호트(#348/#509 가 쓰는 20,800석)를 건드리지 않는다.
--     타이틀 접두사를 'LTC-' 로 분리했고, cleanup_load.sql 의 'LOADTEST-%' 패턴과
--     겹치지 않으므로 서로의 정리 대상에 잡히지 않는다.
--
--   런북: docs/load-test-guide.md §14
--   ⚠ 로컬/부하테스트 전용 DB에서만 실행할 것 (공유/운영 DB 금지).
--
--   실행 (규모마다 1회씩, @perf_tag 를 바꿔가며):
--     GUARD='SET @i_confirm_loadtest_db=1, @perf_tag=''A'', @seats=600'
--     mysql --init-command="$GUARD" ticket_rush < load-test/seed/seed_seat_counts.sql
-- ============================================================================

-- ---- 오실행 가드 -----------------------------------------------------------
-- seed_load.sql 과 동일 규약. 변수가 없으면 없는 테이블을 PREPARE 하다 ERROR 1146 으로 중단된다.
SET @stmt := IF(COALESCE(@i_confirm_loadtest_db, 0) = 1,
                'SELECT ''guard ok'' AS guard',
                'SELECT * FROM `ABORT__run_with_i_confirm_loadtest_db_eq_1`');
PREPARE guard_check FROM @stmt;
EXECUTE guard_check;
DEALLOCATE PREPARE guard_check;

-- ---- 파라미터 --------------------------------------------------------------
-- @mode = 'seed'   : 해당 태그의 코호트를 지우고 파라미터대로 다시 만든다(기본).
--         'expire' : 이미 있는 코호트의 AVAILABLE 좌석 @expire_count 건을 '이미 만료된 HOLD' 로
--                    바꾼다. 공연을 지우지 않으므로 performance_id 가 보존된다 —
--                    SSE 구독자가 붙어 있는 상태에서 이벤트 버스트를 만들 때 쓴다(런북 §14.6).
SET @mode      = COALESCE(@mode, 'seed');
SET @perf_tag  = COALESCE(@perf_tag, 'A');       -- 코호트 식별자. 공연 title = 'LTC-<tag>'
SET @seats     = COALESCE(@seats, 600);          -- 공연당 좌석 수
SET @sold_pct  = COALESCE(@sold_pct, 20);        -- SOLD 비율(%)
SET @hold_pct  = COALESCE(@hold_pct, 20);        -- 미만료 HOLD 비율(%). 나머지는 AVAILABLE
SET @expire_count = COALESCE(@expire_count, 0);  -- @mode='expire' 에서만 쓴다

SET @marker = 'LTC';
SET @title  = CONCAT(@marker, '-', @perf_tag);
SET SESSION cte_max_recursion_depth = 1000000;

-- ---- 파라미터 가드 ---------------------------------------------------------
-- 합이 100 을 넘으면 아래 CASE 의 두 번째 분기가 첫 분기를 먹어 분포가 조용히 달라진다.
-- 리포트의 '상태 분포' 가 실제와 다르면 완료조건 1이 거짓 증적이 되므로 여기서 끊는다.
SET @stmt := IF(@sold_pct >= 0 AND @hold_pct >= 0 AND @sold_pct + @hold_pct <= 100,
                'SELECT ''pct ok'' AS guard',
                'SELECT * FROM `ABORT__sold_pct_plus_hold_pct_must_be_le_100`');
PREPARE pct_check FROM @stmt;
EXECUTE pct_check;
DEALLOCATE PREPARE pct_check;

SET @stmt := IF(@mode IN ('seed', 'expire'),
                'SELECT ''mode ok'' AS guard',
                'SELECT * FROM `ABORT__mode_must_be_seed_or_expire`');
PREPARE mode_check FROM @stmt;
EXECUTE mode_check;
DEALLOCATE PREPARE mode_check;

-- ---- ⚠ 앱 시계와 DB 세션 시계가 다르다 -------------------------------------
-- 배포본 앱 컨테이너는 UTC 로 돌지만 MySQL 컨테이너는 system_time_zone=KST 라 세션 NOW() 가
-- 9시간 앞선다(seed_expired_holds.sql 이 실측에서 확인한 함정). 그런데 집계 쿼리의 `now` 는
-- SeatGetStatusCountsUseCase 가 앱 JVM 시계로 만들어 넘기는 값이다(DB CURRENT_TIMESTAMP 아님).
-- 세션 NOW() 로 hold_expired_at 을 박으면 '미만료로 넣은 HOLD' 가 앱 기준으로는 9시간 미래,
-- '만료로 넣은 HOLD' 는 앱 기준으로 아직 미만료가 되어 상태 분포가 통째로 어긋난다.
SET @app_now = UTC_TIMESTAMP();

-- ============================================================================
-- MODE = seed — 코호트를 지우고 다시 만든다
-- ============================================================================
-- 리셋을 NOT EXISTS idempotency 대신 삭제+재삽입으로 하는 이유: @seats·비율을 바꿔 재실행하는
-- 것이 이 시드의 정상 사용법인데, NOT EXISTS 로는 이전 규모의 잔여 좌석이 그대로 남아 분포가
-- 파라미터와 달라진다. 대신 재실행마다 performance_id 가 바뀌므로 아래 검증 쿼리의 값을
-- k6 의 PERF_ID 로 다시 넘겨야 한다(런북 §14.3).
SET @old_perf_id = (SELECT performance_id FROM performance WHERE title = @title);

DELETE FROM booking WHERE performance_id = @old_perf_id AND @mode = 'seed';
DELETE FROM seat    WHERE performance_id = @old_perf_id AND @mode = 'seed';
DELETE FROM seat_layout WHERE performance_id = @old_perf_id AND @mode = 'seed';
DELETE FROM performance WHERE performance_id = @old_perf_id AND @mode = 'seed';

-- ---- 1) performance --------------------------------------------------------
INSERT INTO performance
  (title, genre, show_date, show_time, duration_minutes, price, total_seats,
   performance_status, created_at, updated_at)
SELECT @title, 'MUSICAL', CURDATE() + INTERVAL 30 DAY, '19:00:00',
       120, 50000, @seats, 'ON_SALE', @app_now, @app_now
FROM DUAL
WHERE @mode = 'seed';

SET @perf_id = (SELECT performance_id FROM performance WHERE title = @title);

-- ---- 2) seat_layout (공연당 1건, performance_id UNIQUE) ---------------------
-- 좌석 번호는 'S-<n>' 단조 증가다(seed_load.sql 의 'A-1' 행/열 형식과 다르다). 행/열 형식은
-- CHAR(64+ri) 때문에 26행을 넘길 수 없어 3,000석 규모를 못 만든다. total_rows/max_cols 는
-- 좌석 생성에 쓰이지 않고 응답에도 실리지 않으므로 규모만 맞춰 채운다.
INSERT INTO seat_layout (performance_id, total_rows, max_cols, created_at, updated_at)
SELECT @perf_id, CEIL(@seats / 50), 50, @app_now, @app_now
FROM DUAL
WHERE @mode = 'seed';

SET @layout_id = (SELECT seat_layout_id FROM seat_layout WHERE performance_id = @perf_id);

-- ---- 3) seat — 상태 분포를 파라미터대로 결정적으로 배분 ---------------------
-- (i-1) % 100 으로 100석 단위 사이클을 만든다. 랜덤을 쓰지 않는 이유는 재현성이다 —
-- 스케일 A(600석)와 B(3,000석)의 곡선을 겹치려면 두 코호트의 상태 비율이 정확히 같아야 한다.
--
-- 미만료 HOLD 의 만료 시각을 +6시간으로 두는 것이 이 시드의 핵심이다. 만료된 HOLD 를 넣으면
-- 60초 주기 SeatStatusScheduler 가 측정 도중 AVAILABLE 로 해제해 상태 분포가 회차 중간에
-- 변한다(chunk-size 25 x max-chunks 80 = tick 당 최대 2,000건). 그러면 좌석 수 대비 곡선의
-- 통제 변수가 깨진다. 만료 HOLD 가 필요한 회차는 @mode='expire' 로 따로 만든다.
INSERT INTO seat
  (seat_layout_id, performance_id, seat_number, seat_status, hold_expired_at,
   created_at, updated_at)
WITH RECURSIVE n(i) AS (
  SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < @seats
)
SELECT @layout_id, @perf_id, CONCAT('S-', n.i),
       CASE
         WHEN ((n.i - 1) % 100) < @sold_pct                          THEN 'SOLD'
         WHEN ((n.i - 1) % 100) < @sold_pct + @hold_pct              THEN 'HOLD'
         ELSE 'AVAILABLE'
       END,
       CASE
         WHEN ((n.i - 1) % 100) >= @sold_pct
          AND ((n.i - 1) % 100) <  @sold_pct + @hold_pct
         THEN @app_now + INTERVAL 6 HOUR
         ELSE NULL
       END,
       @app_now, @app_now
FROM n
WHERE @mode = 'seed';

-- ============================================================================
-- MODE = expire — 이미 있는 코호트에 '만료된 HOLD' 를 만든다 (SSE 큐 포화 회차)
-- ============================================================================
-- 다음 스케줄러 tick(60초)이 이 좌석들을 해제하면서 좌석 1건당 SSE 이벤트 1건을 발행한다.
-- 청크(25건)마다 커밋하고 afterCommit 에서 send 하므로, tick 하나가 executor 큐(1000)에
-- 최대 2,000개의 태스크를 밀어 넣는다 — 큐 용량의 2배다. @expire_count 를 1,000 아래로
-- 두면 거부가 나지 않고 1,500~2,000 이면 확실히 난다.
-- ⚠ MySQL 은 `UPDATE ... LIMIT` 에 변수·식을 쓸 수 없다(구문 오류). 버스트 크기를 통제해야
--   포화 지점을 읽을 수 있으므로 건수 제한을 PREPARE 로 만들어 건다. @mode='seed' 면 이
--   문장은 실행되지 않는다.
SET @stmt := IF(@mode = 'expire',
  CONCAT('UPDATE seat SET seat_status = ''HOLD'', hold_expired_at = @app_now - INTERVAL 1 MINUTE, ',
         'updated_at = @app_now WHERE performance_id = @perf_id AND seat_status = ''AVAILABLE'' ',
         'ORDER BY seat_id LIMIT ', CAST(@expire_count AS CHAR)),
  'SELECT ''expire skipped'' AS note');
PREPARE expire_stmt FROM @stmt;
EXECUTE expire_stmt;
DEALLOCATE PREPARE expire_stmt;

-- ---- 검증 쿼리 -------------------------------------------------------------
-- 이 출력이 리포트 '시딩 규모' 절의 원자료다(#403 완료조건 1).
-- performance_id 를 k6 의 PERF_ID / SSE_PERF_ID 로 넘긴다.
SELECT NOW() AS db_now, @app_now AS app_now_used, @mode AS mode_used;

SELECT
  @title                                             AS title,
  @perf_id                                           AS performance_id,
  COUNT(*)                                           AS total_seats,
  SUM(seat_status = 'AVAILABLE')                     AS available,
  SUM(seat_status = 'SOLD')                          AS sold,
  SUM(seat_status = 'HOLD' AND hold_expired_at >  @app_now) AS hold_live,
  SUM(seat_status = 'HOLD' AND hold_expired_at <= @app_now) AS hold_expired
FROM seat
WHERE performance_id = @perf_id;

-- 집계 API 가 반환할 값의 기대치. /seat-counts 응답과 이 행이 일치해야 한다
-- (available_count 는 만료 HOLD 를 예매 가능으로 선반영한다 — SeatRepository.java:21-22).
SELECT
  COUNT(*)                                                  AS expect_total_count,
  SUM(seat_status = 'AVAILABLE'
      OR (seat_status = 'HOLD' AND hold_expired_at <= @app_now)) AS expect_available_count,
  SUM(seat_status = 'SOLD')                                 AS expect_sold_count,
  SUM(seat_status = 'HOLD' AND hold_expired_at > @app_now)  AS expect_hold_count
FROM seat
WHERE performance_id = @perf_id;
