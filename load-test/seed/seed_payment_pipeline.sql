-- ============================================================================
-- #504 결제확정 → 티켓발급 파이프라인 코호트 시딩 (MySQL 8.0+ / 스키마 ticket_rush)
--
--   `payment-confirmed-topic` 에 이벤트를 직접 주입해 backlog 회복시간을 재기 위한 코호트를
--   만든다. 이벤트 한 건이 두 컨슈머 그룹에서 각각 아래를 요구하므로, 그 전제를 DB 로 만든다.
--
--     booking-group : booking 이 PENDING 이고, payload 의 seat_id 가 booking.seat_id 와 같고,
--                     그 좌석이 HOLD 이며 booking_number 가 일치해야 SOLD 확정이 성립한다.
--     ticket-group  : booking_id 로 티켓이 아직 없어야 발급된다(ticket.booking_id UNIQUE).
--
--   ⚠ 좌석을 예매와 1:1 로 심는다. seed_entry.sql(#402)처럼 좌석 하나를 전 건이 공유하면
--     payload 의 seat_id 를 하나로 고정해야 하고, 그러면 BookingConfirmUseCase 가
--     BOOKING_SEAT_MISMATCH 로 **확정 단계에서** 전건을 죽인다. 통과시켜도 SeatConfirmSoldUseCase 가
--     SEAT_CONFIRM_NOT_OWNED(409) 를 내고 booking 이 SeatConfirmFailedEvent 를 outbox 에 쌓아
--     측정 창에 2차 파동이 얹힌다. #402 가 좌석을 공유할 수 있었던 것은 검표 경로에 seat-service 가
--     아예 없기 때문이며, 전제가 다르다.
--
--   ⚠ 기존 코호트를 건드리지 않는다. 타이틀 접두사 'LTP-' 는 cleanup_load.sql 의 'LOADTEST-%'
--     에도 #403 의 'LTC-%' 에도 안 걸리고, booking_id 대역 2,000,001~ 은 #402 의
--     1,000,001~1,019,399 와 겹치지 않는다.
--
--   런북: docs/load-test-guide.md §15
--   ⚠ 로컬/부하테스트 전용 DB에서만 실행할 것 (공유/운영 DB 금지).
--
--   실행:
--     GUARD='SET @i_confirm_loadtest_db=1, @mode=''seed'', @count=30000'
--     mysql --init-command="$GUARD" ticket_rush < load-test/seed/seed_payment_pipeline.sql
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
--         'reset'  : 회차 반복용. 티켓·inbox 를 지우고 booking 을 PENDING, seat 를 HOLD 로
--                    되돌린다. performance_id·booking_id·seat_id 가 보존되므로 주입 스크립트의
--                    인자를 그대로 재사용할 수 있다.
--         'verify' : 주입 후 정합성 집계. 아무것도 바꾸지 않는다.
SET @mode     = COALESCE(@mode, 'seed');
SET @count    = COALESCE(@count, 30000);   -- 코호트 규모(= 최대 주입 가능 건수)
SET @perf_tag = COALESCE(@perf_tag, 'A');  -- 코호트 식별자. 공연 title = 'LTP-<tag>'

SET @marker    = 'LTP';
SET @title     = CONCAT(@marker, '-', @perf_tag);
SET @bk_prefix = 'LT-P';
SET @bk_base   = 2000000;                  -- booking_id 는 @bk_base + i (i = 1..@count)
SET SESSION cte_max_recursion_depth = 1000000;

SET @stmt := IF(@mode IN ('seed', 'reset', 'verify'),
                'SELECT ''mode ok'' AS guard',
                'SELECT * FROM `ABORT__mode_must_be_seed_reset_or_verify`');
PREPARE mode_check FROM @stmt;
EXECUTE mode_check;
DEALLOCATE PREPARE mode_check;

-- ---- ⚠ 앱 시계와 DB 세션 시계가 다르다 -------------------------------------
-- 배포본 앱 컨테이너는 UTC 로 돌지만 MySQL 컨테이너는 system_time_zone=KST 라 세션 NOW() 가
-- 9시간 앞선다(seed_expired_holds.sql 이 실측에서 확인한 함정). 상태 판정을 앱 JVM 시계로 하는
-- 곳이 여럿이므로 시드는 전부 UTC 로 박는다.
SET @app_now = UTC_TIMESTAMP();

-- ---- ⚠ 두 스케줄러가 코호트를 시한폭탄으로 만든다 ---------------------------
-- (1) BookingExpireUseCase: PENDING 이고 created_at <= now-5분 인 예매를 EXPIRED 로 넘긴다.
--     PAYMENT_WAIT_MINUTES=5, @Scheduled(fixedDelay=60000), 배치 100 x 최대 200회 = tick 당
--     20,000건. created_at 을 현재 시각으로 박으면 시딩 5~6분 뒤 코호트가 통째로 EXPIRED 가 되고,
--     그러면 booking.confirm() 이 BOOKING_EXPIRED 로 죽어 티켓도 좌석 SOLD 도 나오지 않는다.
--     회차의 'baseline 5분' 이 정확히 도화선 길이다.
-- (2) SeatStatusScheduler: 만료된 HOLD 를 60초 주기로 AVAILABLE 로 해제한다. 해제되면
--     confirmSoldById(seatId, bookingNumber, HOLD, SOLD) 가 0행을 갱신해 전건 실패한다.
--
-- 둘 다 시각 비교라 **미래로 밀어** 막는다. 6시간은 한 회차(수십 분)를 충분히 덮는다.
SET @future = @app_now + INTERVAL 6 HOUR;

-- ---- 전용 사용자 -----------------------------------------------------------
-- seed_load.sql 이 만든 계정을 재사용한다. 이 시드는 로그인 계정을 새로 만들지 않는다
-- (인터넷에 노출된 배포본에 계정을 하나라도 덜 만든다).
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
-- 삭제 순서: ticket -> booking -> seat -> seat_layout -> performance (cleanup_load.sql 규약).
SET @old_perf_id = (SELECT performance_id FROM performance WHERE title = @title);

DELETE FROM ticket
 WHERE @mode = 'seed' AND booking_id BETWEEN @bk_base + 1 AND @bk_base + @count;
DELETE FROM booking     WHERE @mode = 'seed' AND performance_id = @old_perf_id;
DELETE FROM seat        WHERE @mode = 'seed' AND performance_id = @old_perf_id;
DELETE FROM seat_layout WHERE @mode = 'seed' AND performance_id = @old_perf_id;
DELETE FROM performance WHERE @mode = 'seed' AND performance_id = @old_perf_id;

-- ---- 1) performance --------------------------------------------------------
INSERT INTO performance
  (title, genre, show_date, show_time, duration_minutes, price, total_seats,
   performance_status, created_at, updated_at)
SELECT @title, 'MUSICAL', CURDATE() + INTERVAL 30 DAY, '19:00:00',
       120, 50000, @count, 'ON_SALE', @app_now, @app_now
FROM DUAL
WHERE @mode = 'seed';

SET @perf_id = (SELECT performance_id FROM performance WHERE title = @title);

-- ---- 2) seat_layout (공연당 1건, performance_id UNIQUE) ---------------------
-- 좌석 번호는 seed_seat_counts.sql 과 같은 'S-<n>' 단조 증가다. seed_load.sql 의 'A-1' 행/열
-- 형식은 CHAR(64+ri) 때문에 26행을 넘길 수 없어 30,000석 규모를 못 만든다.
INSERT INTO seat_layout (performance_id, total_rows, max_cols, created_at, updated_at)
SELECT @perf_id, CEIL(@count / 50), 50, @app_now, @app_now
FROM DUAL
WHERE @mode = 'seed';

SET @layout_id = (SELECT seat_layout_id FROM seat_layout WHERE performance_id = @perf_id);

-- ---- 3) seat — 전부 HOLD, 예매 i 가 쥔 상태로 심는다 ------------------------
-- booking_number 를 여기서 함께 박아 두는 것이 핵심이다. confirmSoldById 는
-- `WHERE seat_id=? AND booking_number=? AND seat_status='HOLD'` 로 갱신하므로 이 셋이 전부
-- 맞아야 SOLD 가 된다.
INSERT INTO seat
  (seat_layout_id, performance_id, seat_number, seat_status, booking_number,
   hold_expired_at, created_at, updated_at)
WITH RECURSIVE n(i) AS (
  SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < @count
)
SELECT @layout_id, @perf_id, CONCAT('S-', n.i), 'HOLD',
       CONCAT(@bk_prefix, LPAD(n.i, 9, '0')),
       @future, @app_now, @app_now
FROM n
WHERE @mode = 'seed';

-- ---- 4) booking — seat 와 1:1, PENDING -------------------------------------
-- seat_id 는 AUTO_INCREMENT 라 예측할 수 없다. 주입 스크립트는 DB 를 보지 않고 순번으로 값을
-- 만들므로 `seat_id = @seat_min + (i-1)` 이 성립해야 하고, 아래 검증 SELECT 가 그 전제를 확인한다.
-- booking_id 는 seed_entry.sql 과 같은 이유로 AUTO_INCREMENT 에 맡기지 않는다 — 주입 스크립트가
-- BOOKING_ID_MIN + idx 로 만들기 때문에 회차마다 대역이 바뀌면 안 된다.
SET @seat_min = (SELECT MIN(seat_id) FROM seat WHERE performance_id = @perf_id);

INSERT INTO booking
  (booking_id, user_id, performance_id, seat_id, booking_number, booking_status,
   confirmed_at, created_at, updated_at)
WITH RECURSIVE n(i) AS (
  SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < @count
)
SELECT @bk_base + n.i, @user_id, @perf_id, @seat_min + n.i - 1,
       CONCAT(@bk_prefix, LPAD(n.i, 9, '0')), 'PENDING',
       NULL, @future, @app_now
FROM n
WHERE @mode = 'seed';

-- ============================================================================
-- MODE = reset — 회차를 다시 돌릴 수 있는 상태로 되돌린다
-- ============================================================================
SET @perf_id  = COALESCE(@perf_id,  (SELECT performance_id FROM performance WHERE title = @title));
SET @seat_min = COALESCE(@seat_min, (SELECT MIN(seat_id) FROM seat WHERE performance_id = @perf_id));

-- (a) 티켓 — ticket.booking_id 가 UNIQUE 라 지우지 않으면 다음 회차가 전건 already_issued 가 된다.
DELETE FROM ticket
 WHERE @mode = 'reset' AND booking_id BETWEEN @bk_base + 1 AND @bk_base + @count;

-- (b) inbox — 회차마다 새 eventId 를 쓰므로 지우지 않으면 회차당 2N 행이 영구 누적된다
--     (INBOX_RETENTION 은 기본 비활성이고 켜도 30일이라 회차 사이에 안 걷힌다).
--     이 토픽은 주입 외에 흐른 적이 없으므로 event_type 으로 잘라도 안전하다.
DELETE FROM inbox
 WHERE @mode = 'reset'
   AND event_type = 'PaymentConfirmed'
   AND consumer_group IN ('booking-group', 'ticket-group');

-- (c) booking — PENDING 복귀. confirmed_at 을 NULL 로 되돌리지 않으면 Booking.confirm() 의
--     멱등 보정 분기가 회차 간 상태를 흐린다. created_at 은 다시 미래로 민다(만료 스케줄러).
UPDATE booking
   SET booking_status = 'PENDING',
       confirmed_at   = NULL,
       created_at     = @future,
       updated_at     = @app_now
 WHERE @mode = 'reset' AND performance_id = @perf_id;

-- (d) seat — HOLD 복귀. booking_number 는 seat_number 로 결정적으로 복원한다
--     (SOLD 전이는 booking_number 를 지우지 않지만, 만료 해제가 끼어들면 NULL 이 될 수 있다).
UPDATE seat
   SET seat_status     = 'HOLD',
       booking_number  = CONCAT(@bk_prefix,
                                LPAD(CAST(SUBSTRING(seat_number, 3) AS UNSIGNED), 9, '0')),
       hold_expired_at = @future,
       updated_at      = @app_now
 WHERE @mode = 'reset' AND performance_id = @perf_id;

-- ============================================================================
-- 검증 SELECT — 이 출력이 리포트 '시딩 규모' 절의 원자료이고, 주입 스크립트의 인자다
-- ============================================================================
SELECT NOW() AS db_now, @app_now AS app_now_utc, @mode AS mode_used, @title AS title;

-- 주입 스크립트에 그대로 넘기는 값.
SELECT @perf_id            AS perf_id,
       @bk_base + 1        AS booking_id_min,
       @seat_min           AS seat_id_min,
       @user_id            AS user_id,
       @count              AS cohort_size;

-- 판정 기준: contiguous_seats = 1, seat_booking_aligned = 1, pending = @count, held = @count,
--            tickets = 0. 하나라도 어긋나면 측정을 진행하지 않는다.
--   contiguous_seats     — seat_id 가 @seat_min 부터 빈틈없이 이어지는가.
--                          주입 스크립트가 seat_id = SEAT_ID_MIN + idx 로 만들기 때문에 필수다.
--   seat_booking_aligned — booking i 의 seat_id 가 정확히 seat 'S-i' 를 가리키는가.
SELECT
  COUNT(*)                                                    AS seats,
  (MAX(seat_id) - MIN(seat_id) + 1 = COUNT(*))                AS contiguous_seats,
  SUM(seat_status = 'HOLD' AND hold_expired_at > @app_now)    AS held,
  SUM(seat_id - @seat_min + 1
      = CAST(SUBSTRING(seat_number, 3) AS UNSIGNED)) = COUNT(*) AS seat_number_ordered
FROM seat
WHERE performance_id = @perf_id;

SELECT
  COUNT(*)                                       AS bookings,
  SUM(booking_status = 'PENDING')                AS pending,
  SUM(booking_status <> 'PENDING')               AS not_pending,
  (MAX(booking_id) - MIN(booking_id) + 1 = COUNT(*)) AS contiguous_bookings,
  SUM(b.seat_id = @seat_min
      + CAST(SUBSTRING(b.booking_number, 5) AS UNSIGNED) - 1) = COUNT(*) AS seat_booking_aligned,
  SUM(created_at > @app_now)                     AS expiry_safe
FROM booking b
WHERE performance_id = @perf_id;

-- ---- MODE = verify 전용 — 주입 후 정합성 -----------------------------------
-- 드레인이 끝난 뒤(lag 0 도달 후) 실행한다. 기대: confirmed = sold = tickets = 주입 건수,
-- stray_events = 0.
--   stray_events — 측정 창에 2차 파동이 없었다는 유일한 사후 증적이다. SeatConfirmFailedEvent 는
--                  좌석 1:1 이 깨졌을 때만, BookingExpiredEvent 는 만료 스케줄러가 코호트를
--                  물었을 때만 나온다. 둘 다 0이어야 회차가 유효하다.
SELECT
  (SELECT COUNT(*) FROM booking
    WHERE performance_id = @perf_id AND booking_status = 'CONFIRMED')          AS confirmed,
  (SELECT COUNT(*) FROM seat
    WHERE performance_id = @perf_id AND seat_status = 'SOLD')                  AS sold,
  (SELECT COUNT(*) FROM ticket
    WHERE booking_id BETWEEN @bk_base + 1 AND @bk_base + @count)               AS tickets,
  (SELECT COUNT(*) FROM inbox
    WHERE event_type = 'PaymentConfirmed' AND consumer_group = 'booking-group') AS inbox_booking,
  (SELECT COUNT(*) FROM inbox
    WHERE event_type = 'PaymentConfirmed' AND consumer_group = 'ticket-group')  AS inbox_ticket,
  (SELECT COUNT(*) FROM outbox
    WHERE event_type IN ('SeatConfirmFailedEvent', 'BookingExpiredEvent')
      AND created_at >= @app_now - INTERVAL 1 HOUR)                            AS stray_events;
