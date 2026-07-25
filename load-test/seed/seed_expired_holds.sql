-- ============================================================================
-- #345 만료 코호트 시딩 — 대량 만료 청크 트랜잭션 before/after 측정용
--   seed_load.sql 이 만든 LOADTEST 공연/좌석/계정을 전제로, 그 중 AVAILABLE 좌석
--   @expired_count 건을 "이미 만료된 HOLD"로 만든다. 좌석 만료 fallback
--   (SeatStatusScheduler -> SeatReleaseExpiredUseCase) 이 다음 tick 에 이 코호트를 집어간다.
--   ⚠ 로컬/부하테스트 전용 DB에서만 실행할 것 (공유/운영 DB 금지).
--
--   seed_load.sql 의 @booking_pct 는 hold_expired_at = NOW() + 5분(미만료)이라 이 측정에 쓸 수 없다.
--   런북: docs/load-test-guide.md §11
--
--   리셋 + 시드가 한 파일에 들어 있어 매 회차 앞에 한 번만 돌리면 된다.
--     mysql --init-command="SET @i_confirm_loadtest_db=1" ... < seed_expired_holds.sql
-- ============================================================================

-- ---- 오실행 가드 -----------------------------------------------------------
-- seed_load.sql 과 동일 규약. 변수가 없으면 없는 테이블을 PREPARE 하다 ERROR 1146 으로 즉시 중단된다.
SET @stmt := IF(COALESCE(@i_confirm_loadtest_db, 0) = 1,
                'SELECT ''guard ok'' AS guard',
                'SELECT * FROM `ABORT__run_with_i_confirm_loadtest_db_eq_1`');
PREPARE guard_check FROM @stmt;
EXECUTE guard_check;
DEALLOCATE PREPARE guard_check;

-- ---- 규모 파라미터 (여기만 조정) -------------------------------------------
SET @expired_count = 2000;   -- 만료 코호트 크기. 2000 = tick당 처리 상한(25 x 80) / 10000 = 최소 5 tick
SET @marker        = 'LOADTEST';
SET @load_email    = 'loadtest@ticketrush.local';
-- 코호트 전용 booking_number 프리픽스. 'LT-' 로 시작하므로 cleanup_load.sql 의 'LT-%' 패턴에도 걸린다.
SET @cohort_prefix = 'LT-X';

SET @load_user_id = (SELECT id FROM `user` WHERE email = @load_email);

-- ---- 1) 리셋 — 직전 회차 코호트 제거 ---------------------------------------
-- 순서는 outbox -> booking -> seat 다. outbox 를 먼저 지워야 전 회차의 미발행 PENDING 행이 릴레이를 타고
-- 새 측정 창에 섞이지 않는다. outbox 대상은 booking 행에서 aggregate_id 를 찾으므로 booking 삭제보다 앞이어야 한다.
-- (docs/load-test-guide.md §10.2 가 #344 측정에서 확립한 순서)

-- 좌석 만료 이벤트: SeatHoldExpiredEvent.aggregateId() = String.valueOf(seatId)
DELETE FROM outbox
 WHERE event_type = 'SeatHoldExpiredEvent'
   AND aggregate_id IN (
     SELECT CAST(b.seat_id AS CHAR) FROM booking b
      WHERE b.booking_number LIKE CONCAT(@cohort_prefix, '%'));

-- 예매 만료 이벤트: BookingExpiredEvent.aggregateId() = String.valueOf(bookingId)
DELETE FROM outbox
 WHERE event_type = 'BookingExpiredEvent'
   AND aggregate_id IN (
     SELECT CAST(b.booking_id AS CHAR) FROM booking b
      WHERE b.booking_number LIKE CONCAT(@cohort_prefix, '%'));

-- 전 회차가 다 소진되지 않고 끝난 경우 좌석이 HOLD 로 남아 있다. 되돌린다.
UPDATE seat s
   SET s.seat_status = 'AVAILABLE', s.booking_number = NULL, s.hold_expired_at = NULL
 WHERE s.booking_number LIKE CONCAT(@cohort_prefix, '%');

DELETE FROM booking WHERE booking_number LIKE CONCAT(@cohort_prefix, '%');

-- ---- 2) 코호트 booking 생성 (PENDING) --------------------------------------
-- created_at 은 NOW() 다. 과거로 당기면 booking-service 의 BookingExpireUseCase(cutoff = now - 5분)가
-- 이 코호트를 따로 물어 좌석 경로 측정이 오염된다. 예매 만료는 SeatHoldExpiredEvent 경유(프로덕션 경로)로만
-- 일어나게 둔다.
INSERT INTO booking
  (user_id, performance_id, seat_id, booking_number, booking_status, created_at, updated_at)
WITH ranked AS (
  SELECT s.seat_id, s.performance_id,
         ROW_NUMBER() OVER (ORDER BY s.seat_id) AS gseq
  FROM seat s
  JOIN performance p ON p.performance_id = s.performance_id
                    AND p.title LIKE CONCAT(@marker, '-%')
  WHERE s.seat_status = 'AVAILABLE'
)
SELECT @load_user_id, r.performance_id, r.seat_id,
       CONCAT(@cohort_prefix, LPAD(r.gseq, 9, '0')), 'PENDING', NOW(), NOW()
FROM ranked r
WHERE r.gseq <= @expired_count;

-- ---- 3) 좌석을 '이미 만료된 HOLD' 로 전이 ----------------------------------
-- hold_expired_at 을 과거로 둬서 시드 완료 시점에 곧바로 만료 대상이 된다. Redis 락 키를 만들지 않으므로
-- SeatReleaseSingleUseCase(Redis 만료 리스너)는 개입하지 않는다 — fallback 경로 단독 측정이 성립한다.
UPDATE seat s
  JOIN booking b ON b.seat_id = s.seat_id
                AND b.booking_number LIKE CONCAT(@cohort_prefix, '%')
   SET s.seat_status     = 'HOLD',
       s.booking_number  = b.booking_number,
       s.hold_expired_at = NOW() - INTERVAL 1 MINUTE
 WHERE s.seat_status = 'AVAILABLE' AND b.booking_status = 'PENDING';

-- ---- 검증 쿼리 -------------------------------------------------------------
-- cohort_bookings 와 expired_hold_seats 가 @expired_count 와 모두 같아야 한다.
-- 작으면 LOADTEST AVAILABLE 좌석이 부족한 것이다 -> seed_load.sql 의 @cols_per 를 키워 재시딩한다.
SELECT
  @expired_count AS requested,
  (SELECT COUNT(*) FROM booking
     WHERE booking_number LIKE CONCAT(@cohort_prefix, '%'))                  AS cohort_bookings,
  (SELECT COUNT(*) FROM seat
     WHERE booking_number LIKE CONCAT(@cohort_prefix, '%')
       AND seat_status = 'HOLD' AND hold_expired_at <= NOW())                AS expired_hold_seats,
  (SELECT COUNT(*) FROM seat s
     JOIN performance p ON p.performance_id = s.performance_id
                       AND p.title LIKE CONCAT(@marker, '-%')
    WHERE s.seat_status = 'AVAILABLE')                                       AS remaining_available;
