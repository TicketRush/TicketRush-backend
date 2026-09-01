-- ============================================================================
-- #633 payment→booking 동기 조회 왕복 실측용 코호트 시딩 (MySQL 8.0+ / 스키마 ticket_rush)
--
--   POST /api/v1/payment/confirm 을 **PG 에 도달하지 않는 요청**으로 태워서, 그 앞의 booking
--   동기 왕복(BookingRestClient.getBooking)만 반복시키기 위한 코호트다.
--
--   PaymentConfirmUseCase 의 순서가 이 설계의 전부다.
--     (1) payment 가 COMPLETED 인가            → 로컬 DB. 있으면 PAYMENT_409_001 로 끊긴다.
--     (2) expired_booking 에 있는가            → 로컬 DB. 있으면 BOOKING_409_003 으로 끊긴다.
--     (3) assertBookingIsPayable               → ★ 여기서 booking 왕복 1회가 일어난다.
--     (4) PG 승인                              → (3) 이 PENDING 아닌 상태를 만나면 도달하지 않는다.
--
--   그래서 코호트는 "(1)(2)를 통과하고 (3)에서 끊기는" 상태여야 한다.
--     · payment 행이 없어야 하고(새 booking_id 대역이라 자연히 없다)
--     · expired_booking 에 없어야 하고(같은 이유)
--     · booking_status 가 PENDING 이 아니어야 하며(CONFIRMED 로 심는다)
--     · booking.user_id 가 로그인 계정과 같아야 한다. #572 소유자 대조는 getBooking 이 200 으로
--       정상 반환된 **뒤** UseCase 가 하므로, 불일치해도 Timer 에는 outcome=success 로 기록된다.
--       그 요청은 상태 판정까지 가지 않으므로 성격이 다른 표본이 정상 분포에 섞인다.
--
--   ⚠ PENDING 이 한 건이라도 섞이면 그 요청은 (4)까지 내려가 **실제 Toss 승인을 시도한다.**
--     아래 검증 SELECT 의 pending 이 0 이 아니면 회차를 시작하지 않는다. 이것이 1차 방어선이고,
--     시나리오의 응답 code 킬 스위치가 2차다(docs/load-test-guide.md §17.3).
--
--   ⚠ 기존 코호트를 건드리지 않는다. 타이틀 접두사 'LTR-' 는 cleanup_load.sql 의 'LOADTEST-%',
--     #504 의 'LTP-%', #403 의 'LTC-%', 대기열의 'LTQ-%' 어디에도 걸리지 않고,
--     booking_id 대역 3,000,001~ 은 #402(1,000,001~)·#504(2,000,001~)와 겹치지 않는다.
--
--   런북: docs/load-test-guide.md §17
--   ⚠ 로컬/부하테스트 전용 DB에서만 실행할 것 (공유/운영 DB 금지).
--
--   실행:
--     GUARD='SET @i_confirm_loadtest_db=1, @mode=''seed'', @count=10000'
--     mysql --init-command="$GUARD" ticket_rush < load-test/seed/seed_payment_booking_roundtrip.sql
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
-- @mode = 'seed'   : 코호트를 지우고 파라미터대로 다시 만든다(기본).
--         'verify' : 상태 점검만 한다. 아무것도 바꾸지 않는다.
--
-- 'reset' 모드가 없는 것은 이 회차가 **코호트를 소모하지 않기** 때문이다. (3)에서 예외로 끊기는
-- 경로는 DB 쓰기가 하나도 없다 — recordFailedPayment 는 PG try 블록 안에서만 호출되고
-- saveAndFlush 는 그보다 뒤에 있다. 같은 행을 몇 번을 조회하든 상태가 변하지 않으므로
-- 회차를 반복해도 되돌릴 것이 없다.
SET @mode  = COALESCE(@mode, 'seed');
SET @count = COALESCE(@count, 10000);      -- 코호트 규모(= 라운드로빈 주기)

SET @marker    = 'LTR';
SET @perf_tag  = COALESCE(@perf_tag, 'A');
SET @title     = CONCAT(@marker, '-', @perf_tag);
SET @bk_prefix = 'LT-R';
SET @bk_base   = 3000000;                  -- booking_id 는 @bk_base + i (i = 1..@count)
SET @price     = 50000;
SET SESSION cte_max_recursion_depth = 1000000;

SET @stmt := IF(@mode IN ('seed', 'verify'),
                'SELECT ''mode ok'' AS guard',
                'SELECT * FROM `ABORT__mode_must_be_seed_or_verify`');
PREPARE mode_check FROM @stmt;
EXECUTE mode_check;
DEALLOCATE PREPARE mode_check;

-- ---- ⚠ 앱 시계와 DB 세션 시계가 다르다 -------------------------------------
-- 배포본 앱 컨테이너는 UTC 로 돌지만 MySQL 컨테이너는 system_time_zone=KST 라 세션 NOW() 가
-- 9시간 앞선다(seed_expired_holds.sql 이 실측에서 확인한 함정). 시드는 전부 UTC 로 박는다.
SET @app_now = UTC_TIMESTAMP();

-- ---- 전용 사용자 -----------------------------------------------------------
-- seed_load.sql 이 만든 계정을 재사용한다(인터넷에 노출된 배포본에 계정을 하나라도 덜 만든다).
-- 이 계정으로 k6 가 로그인하고, booking.user_id 도 같은 값이라 #572 소유자 대조를 통과한다.
SET @load_email = 'loadtest@ticketrush.local';
SET @user_id    = (SELECT id FROM `user` WHERE email = @load_email);

SET @stmt := IF(@user_id IS NOT NULL,
                'SELECT ''user ok'' AS guard',
                'SELECT * FROM `ABORT__run_seed_load_sql_first_loadtest_user_missing`');
PREPARE user_check FROM @stmt;
EXECUTE user_check;
DEALLOCATE PREPARE user_check;

-- ============================================================================
-- MODE = seed — 코호트를 지우고 다시 만든다
-- ============================================================================
-- 삭제 순서: booking -> seat -> seat_layout -> performance (cleanup_load.sql 규약).
-- ticket 은 이 코호트가 만들지 않는다(확정 이벤트를 흘리지 않으므로 발급될 일이 없다).
--
-- ⚠ performance_id 를 스칼라 서브쿼리로 먼저 뽑지 않는다. performance.title 은 UNIQUE 가 아니라
--   이전 시드가 중간에 실패해 동명 행이 둘 남으면 ERROR 1242(Subquery returns more than 1 row)로
--   죽는다. title 로 조인해 지우면 잔여 행이 몇 개든 한 번에 정리된다.
DELETE b FROM booking b
JOIN performance p ON p.performance_id = b.performance_id
WHERE @mode = 'seed' AND p.title = @title;

DELETE s FROM seat s
JOIN performance p ON p.performance_id = s.performance_id
WHERE @mode = 'seed' AND p.title = @title;

DELETE sl FROM seat_layout sl
JOIN performance p ON p.performance_id = sl.performance_id
WHERE @mode = 'seed' AND p.title = @title;

DELETE FROM performance WHERE @mode = 'seed' AND title = @title;

-- ---- 1) performance --------------------------------------------------------
INSERT INTO performance
  (title, genre, show_date, show_time, duration_minutes, price, total_seats,
   performance_status, created_at, updated_at)
SELECT @title, 'MUSICAL', CURDATE() + INTERVAL 30 DAY, '19:00:00',
       120, @price, @count, 'ON_SALE', @app_now, @app_now
FROM DUAL
WHERE @mode = 'seed';

-- MAX() 로 감싼다. title 은 UNIQUE 가 아니라 동명 행이 둘 남으면 ERROR 1242 로 죽는데,
-- 이 줄은 @mode 가드가 없어 verify 모드에서도 실행된다 — verify 는 DELETE 를 건너뛰므로
-- 정확히 그 상황(동명 행 잔존)에서 §17.3 사전 게이트 자체를 통과할 수 없게 된다.
SET @perf_id = (SELECT MAX(performance_id) FROM performance WHERE title = @title);

-- ---- 2) seat_layout (공연당 1건, performance_id UNIQUE) ---------------------
INSERT INTO seat_layout (performance_id, total_rows, max_cols, created_at, updated_at)
SELECT @perf_id, CEIL(@count / 50), 50, @app_now, @app_now
FROM DUAL
WHERE @mode = 'seed';

SET @layout_id = (SELECT seat_layout_id FROM seat_layout WHERE performance_id = @perf_id);

-- ---- 3) seat — 전부 SOLD ---------------------------------------------------
-- 이 회차는 좌석을 읽지 않는다(왕복은 booking 단건 조회 하나다). 그래도 1:1 로 심는 것은
-- booking.seat_id 가 NOT NULL 이고, 실재하지 않는 좌석을 가리키는 행을 남기면 다른 회차·관리자
-- 집계에서 정합성 오류로 오인되기 때문이다.
-- SOLD 로 두는 이유는 SeatStatusScheduler 때문이다. 그 스케줄러는 '만료된 HOLD' 만 AVAILABLE 로
-- 되돌리므로 SOLD 는 건드리지 않는다 — HOLD 로 심으면 hold_expired_at 을 미래로 미는 관리가
-- 따라붙는데, 이 회차에는 아무 이득이 없다.
INSERT INTO seat
  (seat_layout_id, performance_id, seat_number, seat_status, booking_number,
   hold_expired_at, created_at, updated_at)
WITH RECURSIVE n(i) AS (
  SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < @count
)
SELECT @layout_id, @perf_id, CONCAT('S-', n.i), 'SOLD',
       CONCAT(@bk_prefix, LPAD(n.i, 9, '0')),
       NULL, @app_now, @app_now
FROM n
WHERE @mode = 'seed';

-- ---- 4) booking — seat 와 1:1, CONFIRMED ------------------------------------
-- ★ CONFIRMED 가 이 시드의 핵심이다. PENDING 이 아니므로 assertBookingIsPayable 이
--   BOOKING_CONFIRM_NOT_ALLOWED(409, BOOKING_409_002)로 끊고 PG 로 내려가지 않는다.
--   EXPIRED 를 쓰지 않는 이유는 응답이 BOOKING_409_003 이 되어 (2)단계에서 끊긴 요청과
--   본문 code 가 같아져, 킬 스위치가 "왕복이 실제로 일어났는가"를 가릴 수 없게 되기 때문이다.
--
--   CONFIRMED 는 만료 스케줄러(BookingExpireUseCase)의 대상도 아니다 — 그쪽은 PENDING 만 본다.
--   그래서 #504 처럼 created_at 을 미래로 밀 필요가 없다.
--
--   paid_amount 는 CONFIRMED 예매의 정상 상태라 채운다(#561). 비워 두면 관리자 매출 집계가
--   이 코호트를 '백필 전 행' 으로 따로 세게 된다.
-- ⚠ seat_id 연속성을 여기서 **미리** 검증한다. 아래 INSERT 가 seat_id = MIN + (i-1) 을 가정하는데,
--   AUTO_INCREMENT 가 동시 INSERT 로 갈라지면 booking 이 엉뚱한 좌석을 가리킨 채 심긴다.
--   검증을 끝의 SELECT 에만 두면 잘못 심고 난 **뒤에야** 드러난다. 이 회차는 seat 를 읽지 않아
--   측정에는 영향이 없지만, 1:1 로 심는 취지(정합성 오인 방지) 자체가 무너진다.
SET @seat_min = (SELECT MIN(seat_id) FROM seat WHERE performance_id = @perf_id);
SET @seat_contiguous = (SELECT MAX(seat_id) - MIN(seat_id) + 1 = COUNT(*)
                          FROM seat WHERE performance_id = @perf_id);

SET @stmt := IF(@mode <> 'seed' OR COALESCE(@seat_contiguous, 0) = 1,
                'SELECT ''seat ok'' AS guard',
                'SELECT * FROM `ABORT__seat_ids_not_contiguous_rerun_seed`');
PREPARE seat_check FROM @stmt;
EXECUTE seat_check;
DEALLOCATE PREPARE seat_check;

INSERT INTO booking
  (booking_id, user_id, performance_id, seat_id, booking_number, booking_status,
   confirmed_at, paid_amount, created_at, updated_at)
WITH RECURSIVE n(i) AS (
  SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < @count
)
SELECT @bk_base + n.i, @user_id, @perf_id, @seat_min + n.i - 1,
       CONCAT(@bk_prefix, LPAD(n.i, 9, '0')), 'CONFIRMED',
       @app_now, @price, @app_now, @app_now
FROM n
WHERE @mode = 'seed';

-- ============================================================================
-- 검증 SELECT — 이 출력이 리포트 '시딩 규모' 절의 원자료이고, 시나리오 env 의 인자다
-- ============================================================================
SET @perf_id  = COALESCE(@perf_id,
                         (SELECT MAX(performance_id) FROM performance WHERE title = @title));
SET @seat_min = COALESCE(@seat_min, (SELECT MIN(seat_id) FROM seat WHERE performance_id = @perf_id));

SELECT NOW() AS db_now, @app_now AS app_now_utc, @mode AS mode_used, @title AS title;

-- k6 에 그대로 넘기는 값 (RT_BOOKING_ID_MIN / RT_SEAT_ID_MIN / RT_COHORT_SIZE).
SELECT @perf_id     AS perf_id,
       @bk_base + 1 AS booking_id_min,
       @seat_min    AS seat_id_min,
       @user_id     AS user_id,
       @count       AS cohort_size;

-- ★ 안전 게이트. pending 이 0 이 아니면 **회차를 시작하지 않는다** — 그 행들은 PG 까지 내려가
--   실제 Toss 승인을 시도한다. owner_ok 가 count 와 다르면 소유자 대조에서 404 로 뒤집혀
--   Timer 가 outcome=not_found 로 기록되고 분포가 오염된다.
SELECT
  COUNT(*)                                            AS bookings,
  SUM(booking_status = 'PENDING')                     AS pending,
  SUM(booking_status = 'CONFIRMED')                   AS confirmed,
  SUM(user_id = @user_id)                             AS owner_ok,
  (MAX(booking_id) - MIN(booking_id) + 1 = COUNT(*))  AS contiguous_bookings,
  (MIN(booking_id) = @bk_base + 1)                    AS booking_id_min_ok
FROM booking
WHERE performance_id = @perf_id;

-- ★ (1)(2) 단계에서 조기 종료되지 않는지 확인한다. 둘 다 0 이어야 왕복이 실제로 일어난다.
--   payments_on_cohort 가 0 이 아니면 그 행은 PAYMENT_409_001 로 (1)에서 끊겨 왕복이 없고,
--   expired_on_cohort 가 0 이 아니면 BOOKING_409_003 으로 (2)에서 끊겨 역시 왕복이 없다.
--   측정군인데 왕복이 없는 요청이 섞이면 Timer count 가 요청 수보다 적어진다(무효 판정 조건).
SELECT
  (SELECT COUNT(*) FROM payment
    WHERE booking_id BETWEEN @bk_base + 1 AND @bk_base + @count)          AS payments_on_cohort,
  (SELECT COUNT(*) FROM expired_booking
    WHERE booking_id BETWEEN @bk_base + 1 AND @bk_base + @count)          AS expired_on_cohort;

SELECT
  COUNT(*)                                     AS seats,
  SUM(seat_status = 'SOLD')                    AS sold,
  (MAX(seat_id) - MIN(seat_id) + 1 = COUNT(*)) AS contiguous_seats
FROM seat
WHERE performance_id = @perf_id;
