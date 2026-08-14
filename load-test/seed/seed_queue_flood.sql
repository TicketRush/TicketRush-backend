-- ============================================================================
-- 대기열 1만 VU 유입 제어 회차(#549 / #472) 전용 시딩 (MySQL 8.0+ / 스키마 ticket_rush)
--   생성: user @cohort_size 행 -> performance 1건 -> seat_layout -> seat @cohort_size 석
--   마커: title 'LTQ-%' / email 'ltq-%@ticketrush.local' -> cleanup_load.sql 이 일괄 삭제.
--   ⚠ 로컬/부하테스트 전용 DB에서만 실행할 것 (공유/운영 DB 금지).
--
-- ---- 왜 seed_load.sql 로 안 되는가 -----------------------------------------
-- flood 프로파일의 마지막 단계인 예매는 대기열과 달리 DB를 탄다. BookingValidateReferencesUseCase 가
--   ① existsUserById(userId)                      -> user 행이 실제로 있어야 한다
--   ② existsSeatByIdAndPerformanceId(seatId, pid) -> 좌석이 그 공연 것이어야 한다
-- 를 요구하는데, seed_load.sql 은 계정 1개 + 공연당 600석(20행 x 30열)이라 1만 VU 를 못 채운다.
-- 계정이 모자라면 1만 건의 예매가 전부 USER_NOT_FOUND(404)로 되돌아와, 예매 경로 RPS 를 재려던
-- 회차가 검증 단계에서 튕기는 비용을 재게 된다.
--
-- ---- 비밀번호를 만들지 않는 이유 -------------------------------------------
-- existsUserById 는 user 행만 본다. k6 는 게이트웨이와 같은 시크릿으로 JWT 를 직접 서명하므로
-- 로그인을 하지 않는다(1만 번의 bcrypt cost 10 은 2 vCPU 를 통째로 먹어 auth-service 를 재게 된다).
-- 따라서 user_account 를 만들지 않는다 — 계정 1만 개를 시딩하면서 로그인 가능한 계정은 0개다.
-- ============================================================================

-- ---- 접속 charset 고정 ------------------------------------------------------
-- ⚠ 이게 없으면 "실행 플래그에 따라 되고 안 되고가 갈린다".
-- 대상 컬럼(user.email·performance.title)은 utf8mb4_unicode_ci 인데 **사용자 변수는 접속 collation 을
-- 따르고**, 이 컨테이너의 기본 접속값은 latin1_swedish_ci 다. 그래서 변수로 만든 문자열을 컬럼과
-- 비교하면 접속 방식마다 다른 실패가 난다 — 기본 접속이면 latin1 이라 COLLATE 지정이 ERROR 1253 이고,
-- --default-character-set=utf8mb4 로 붙으면 utf8mb4_0900_ai_ci 가 되어 ERROR 1267 (Illegal mix of
-- collations) 이다. 여기서 컬럼과 같은 collation 으로 못 박아 양쪽을 한 번에 없앤다.
-- (문자열 리터럴은 coercibility 가 낮아 컬럼 쪽이 이기므로 이 문제가 없다. 변수만 해당된다.)
SET NAMES utf8mb4 COLLATE utf8mb4_unicode_ci;

-- ---- 오실행 가드 (seed_load.sql 과 동일) -----------------------------------
--   mysql --init-command="SET @i_confirm_loadtest_db=1" ... < seed_queue_flood.sql
-- 변수가 없으면 없는 테이블을 PREPARE 하다 ERROR 1146 으로 즉시 중단되고 본문은 돌지 않는다.
SET @stmt := IF(COALESCE(@i_confirm_loadtest_db, 0) = 1,
                'SELECT ''guard ok'' AS guard',
                'SELECT * FROM `ABORT__run_with_i_confirm_loadtest_db_eq_1`');
PREPARE guard_check FROM @stmt;
EXECUTE guard_check;
DEALLOCATE PREPARE guard_check;

-- ---- 규모 파라미터 (여기만 조정) -------------------------------------------
-- 코호트 = VU 수. 좌석은 VU 당 1석을 비가역 소모하므로 둘이 같아야 한다.
SET @cohort_size = 10000;
SET @rows_per    = 25;    -- 'A'~'Y'. CHAR(64+n) 이라 26 이하로 유지할 것
SET @cols_per    = 400;   -- rows * cols = @cohort_size 가 되게 맞춘다
SET @marker      = 'LTQ';
SET @perf_title  = CONCAT(@marker, '-000001');
-- 재귀 CTE 로 1만 행을 만든다. 기본 깊이 1000 으로는 코호트를 못 채운다.
SET SESSION cte_max_recursion_depth = 100000;

-- ---- 1) 사용자 코호트 -------------------------------------------------------
-- email 이 UNIQUE 라 재실행해도 중복이 안 생긴다. user_role='MEMBER' 는 seed_load.sql 과 같다.
-- 한 문장으로 넣어야 id 가 연속으로 잡힌다 — 시나리오가 userId = QUEUE_USER_ID_MIN + VU번호 로
-- 매기므로 중간에 구멍이 생기면 그 VU 의 예매가 통째로 404 가 된다. 아래 검증 쿼리가 이를 잡는다.
INSERT INTO `user` (name, email, user_role, created_at, updated_at)
WITH RECURSIVE n(i) AS (
  SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < @cohort_size
)
SELECT @marker, CONCAT(LOWER(@marker), '-', LPAD(n.i, 5, '0'), '@ticketrush.local'),
       'MEMBER', NOW(), NOW()
FROM n
WHERE NOT EXISTS (
  SELECT 1 FROM `user` u
  WHERE u.email = CONCAT(LOWER(@marker), '-', LPAD(n.i, 5, '0'), '@ticketrush.local')
);

-- ---- 2) performance (1건) --------------------------------------------------
INSERT INTO performance
  (title, genre, show_date, show_time, duration_minutes, price, total_seats,
   performance_status, created_at, updated_at)
SELECT @perf_title, 'MUSICAL', CURDATE() + INTERVAL 30 DAY, '19:00:00',
       120, 50000, @rows_per * @cols_per, 'ON_SALE', NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM performance p WHERE p.title = @perf_title);

SET @perf_id = (SELECT performance_id FROM performance WHERE title = @perf_title);

-- ---- 3) seat_layout (performance_id UNIQUE) --------------------------------
INSERT INTO seat_layout (performance_id, total_rows, max_cols, created_at, updated_at)
SELECT @perf_id, @rows_per, @cols_per, NOW(), NOW()
FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM seat_layout sl WHERE sl.performance_id = @perf_id);

SET @layout_id = (SELECT seat_layout_id FROM seat_layout WHERE performance_id = @perf_id);

-- ---- 4) seat (rows x cols, 전부 AVAILABLE) ---------------------------------
-- 좌석 id 도 연속이어야 한다(위 사용자와 같은 이유). ORDER BY 로 삽입 순서를 고정해
-- seat_number 순서와 seat_id 순서가 어긋나지 않게 한다.
INSERT INTO seat (seat_layout_id, performance_id, seat_number, seat_status, created_at, updated_at)
WITH RECURSIVE
  r(ri) AS (SELECT 1 UNION ALL SELECT ri + 1 FROM r WHERE ri < @rows_per),
  c(ci) AS (SELECT 1 UNION ALL SELECT ci + 1 FROM c WHERE ci < @cols_per)
SELECT @layout_id, @perf_id, CONCAT(CHAR(64 + r.ri), '-', c.ci), 'AVAILABLE', NOW(), NOW()
FROM r CROSS JOIN c
WHERE NOT EXISTS (
  SELECT 1 FROM seat s
  WHERE s.performance_id = @perf_id
    AND s.seat_number = CONCAT(CHAR(64 + r.ri), '-', c.ci)
)
ORDER BY r.ri, c.ci;

-- ---- 검증 + k6 실행 인자 ---------------------------------------------------
-- 아래 k6_env_arg 4줄을 그대로 k6 실행에 넣는다. 오프셋이 -1 인 이유: 시나리오가
--   userId = QUEUE_USER_ID_MIN + 1 + exec.scenario.iterationInTest 로 매긴다.
--   (#555 에서 exec.vu.idInTest(1-base) → iterationInTest(0-base) 로 바꿨다. arrival-rate 는
--    VU 를 재사용하므로 VU 번호로 사람을 매기면 같은 계정·좌석이 여러 번 나온다. 오프셋 값
--    자체는 바뀌지 않는다 — 0-base 보정을 시나리오 쪽에서 한다.)
-- 연속성이 'GAP' 이면 그대로 쓰면 안 된다 — 구멍에 걸린 VU 의 예매가 404 로 튕겨
-- 예매 경로 RPS 가 과소 집계된다. cleanup 후 재시딩할 것.
-- 별칭은 ASCII 로 둔다. 이 결과는 SSH 를 거쳐 Windows 터미널까지 오는데, 그 경로의 인코딩까지
-- 보장할 수는 없다 — 판정에 쓰는 값이라 깨질 여지를 두지 않는다.
SET @email_like = CONCAT(LOWER(@marker), '-%@ticketrush.local');

SELECT 'user cohort' AS item,
       COUNT(*) AS rows_seeded, MIN(id) AS min_id, MAX(id) AS max_id,
       IF(MAX(id) - MIN(id) + 1 = COUNT(*), 'OK', 'GAP') AS contiguous
FROM `user` WHERE email LIKE @email_like
UNION ALL
SELECT 'seat pool',
       COUNT(*), MIN(seat_id), MAX(seat_id),
       IF(MAX(seat_id) - MIN(seat_id) + 1 = COUNT(*), 'OK', 'GAP')
FROM seat WHERE performance_id = @perf_id;

SELECT CONCAT('QUEUE_PERF_ID=', @perf_id) AS k6_env_arg
UNION ALL
SELECT CONCAT('QUEUE_USER_ID_MIN=', (SELECT MIN(id) - 1 FROM `user` WHERE email LIKE @email_like))
UNION ALL
SELECT CONCAT('QUEUE_SEAT_ID_MIN=', (SELECT MIN(seat_id) - 1 FROM seat WHERE performance_id = @perf_id))
UNION ALL
SELECT CONCAT('QUEUE_FLOOD_VUS=', @cohort_size);
