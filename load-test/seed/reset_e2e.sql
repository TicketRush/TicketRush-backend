-- ============================================================================
-- #348 오픈런 e2e 회차 간 리셋 — 앱이 만든 예매/HOLD 만 되돌린다
--   seed_load.sql 이 만든 LOADTEST 공연/좌석/계정은 그대로 두고, 부하 중 앱이 생성한
--   booking 과 그로 인해 전이된 seat 만 원상복구한다. 좌석 규모가 크면 재시딩
--   (cleanup_load.sql -> seed_load.sql)이 비싸서 회차마다 돌릴 수 없기 때문이다.
--   ⚠ 로컬/부하테스트 전용 DB에서만 실행할 것 (공유/운영 DB 금지).
--
--   런북: docs/load-test-guide.md §13
--     mysql --init-command="SET @i_confirm_loadtest_db=1" ... < reset_e2e.sql
--
--   ⚠ 이 파일은 Redis 를 건드리지 못한다. 좌석 락 키(seat:lock:*)는 성공 경로에 unlock 이 없어
--     TTL 5분간 살아남으므로 리셋과 짝을 이뤄 지워야 한다 — 절차는 런북 §13.3.
-- ============================================================================

-- ---- 오실행 가드 -----------------------------------------------------------
-- seed_load.sql 과 동일 규약. 변수가 없으면 없는 테이블을 PREPARE 하다 ERROR 1146 으로 즉시 중단된다.
SET @stmt := IF(COALESCE(@i_confirm_loadtest_db, 0) = 1,
                'SELECT ''guard ok'' AS guard',
                'SELECT * FROM `ABORT__run_with_i_confirm_loadtest_db_eq_1`');
PREPARE guard_check FROM @stmt;
EXECUTE guard_check;
DEALLOCATE PREPARE guard_check;

SET @marker = 'LOADTEST';

-- ---- ⚠ 시드 코호트는 건드리지 않는다 ---------------------------------------
-- seed_entry.sql(#402 검표)은 LOADTEST 첫 공연의 MIN seat_id 에 booking 25,000 건을 심고 그 위에
-- ticket 행을 만든다. 공연 기준으로 booking 을 통째로 지우면 그 코호트가 함께 날아가고 ticket 이
-- 고아가 된다. seed_load.sql(@booking_pct)·seed_expired_holds.sql(#345) 코호트도 마찬가지다.
--
-- 세 시드가 만든 booking_number 는 전부 'LT-' 로 시작한다(LT- / LT-X / LT-E). 앱이 만드는 번호는
-- BookingNumberGenerator 가 찍는 'XXXXX-XXXXX' 형식이라 절대 겹치지 않는다. 그래서 대상을
-- "LOADTEST 공연 AND booking_number NOT LIKE 'LT-%'" 로 좁힌다.
SET @app_prefix_guard = 'LT-%';

-- ---- 1) outbox 미발행분 제거 -----------------------------------------------
-- 순서는 outbox -> seat -> booking 이다(§10.2 가 #344 에서 확립). 전 회차의 미발행 PENDING 행이
-- 남아 있으면 릴레이가 다음 회차에 그대로 뱉어 이전 이벤트가 새 측정 창에 섞인다.
--
-- SENT 는 릴레이가 다시 집지 않아 다음 창을 오염시키지 못하므로 남긴다. aggregate_id 로 코호트를
-- 특정하지 않는 것은 outbox 에 (event_type, aggregate_id) 인덱스가 없어서다 — status 조건은
-- idx_outbox_status_created_at 을 타고, 드레인이 끝난 상태에서는 보통 0행이다
-- (seed_expired_holds.sql 이 같은 이유로 같은 형태를 쓴다).
--   ⚠ 이 DELETE 는 코호트 밖의 미발행 이벤트까지 지운다. 부하테스트 전용 DB 전제에서만 성립한다.
DELETE FROM outbox
 WHERE status IN ('PENDING', 'FAILED')
   AND event_type IN ('BookingCreatedEvent', 'SeatHoldFailedEvent',
                      'SeatHoldExpiredEvent', 'BookingExpiredEvent');

-- ---- 2) 좌석 되돌리기 -------------------------------------------------------
-- 좌석은 한 번 HOLD 되면 스스로 AVAILABLE 로 돌아오지 않는다(만료 스케줄러를 기다려야 한다).
-- version 컬럼(#427 낙관적 락)은 건드리지 않는다 — 값이 그대로면 다음 회차의 JPA 읽기/쓰기에
-- 문제가 없고, 올리면 오히려 앱이 본 적 없는 버전을 만들게 된다.
UPDATE seat s
  JOIN performance p ON p.performance_id = s.performance_id
                    AND p.title LIKE CONCAT(@marker, '-%')
   SET s.seat_status    = 'AVAILABLE',
       s.booking_number = NULL,
       s.hold_expired_at = NULL
 WHERE s.seat_status <> 'AVAILABLE'
   AND (s.booking_number IS NULL OR s.booking_number NOT LIKE @app_prefix_guard);

-- ---- 3) 앱이 만든 예매 삭제 -------------------------------------------------
DELETE b
  FROM booking b
  JOIN performance p ON p.performance_id = b.performance_id
                    AND p.title LIKE CONCAT(@marker, '-%')
 WHERE b.booking_number NOT LIKE @app_prefix_guard;

-- ---- inbox 는 지우지 않는다 -------------------------------------------------
-- inbox 는 이벤트 ID 기준 멱등 기록이다. 새 회차의 booking 은 새 이벤트 ID 를 만들므로 남아 있어도
-- 다음 회차를 막지 않는다. 오히려 지우면 chaos/verify-inbox.sql 의 멱등 검증 근거가 사라진다.
-- 회차 간 비교는 절대값이 아니라 델타로 읽는다.

-- ---- 검증 쿼리 -------------------------------------------------------------
-- leftover_app_bookings 는 0 이어야 한다.
SELECT
  (SELECT COUNT(*) FROM booking b
     JOIN performance p ON p.performance_id = b.performance_id
                       AND p.title LIKE CONCAT(@marker, '-%')
    WHERE b.booking_number NOT LIKE @app_prefix_guard)                       AS leftover_app_bookings,
  (SELECT COUNT(*) FROM seat s
     JOIN performance p ON p.performance_id = s.performance_id
                       AND p.title LIKE CONCAT(@marker, '-%')
    WHERE s.seat_status <> 'AVAILABLE')                                      AS non_available_seats;

-- 공연별 AVAILABLE 좌석의 규모와 seat_id 간격. openrun-e2e.js 의 setup() 이 (min + step*offset) 으로
-- 산술 배정하므로 **distinct_steps 가 1** 이어야 그 공연을 예매 대상으로 쓸 수 있다.
--
-- seat_id 는 공연 안에서 연속이 아니라 등차다. seed_load.sql 의 `seat_layout CROSS JOIN r CROSS JOIN c`
-- 를 MySQL 이 (r,c) 바깥 루프로 실행해 공연들이 인터리브로 삽입되기 때문이고, 그래서 step 이 보통
-- 공연 수와 같다(공연 10건 시딩 -> step 10). distinct_steps 가 2 이상이면 중간 좌석을 다른 코호트가
-- 점유한 상태이며, 그대로 돌리면 409 가 정상 경합으로 위장되어 측정이 조용히 오염된다.
SELECT g.performance_id,
       g.title,
       g.available_seats,
       g.seat_id_min,
       g.seat_id_max,
       d.distinct_steps,
       d.step
  FROM (SELECT p.performance_id, p.title,
               COUNT(*)       AS available_seats,
               MIN(s.seat_id) AS seat_id_min,
               MAX(s.seat_id) AS seat_id_max
          FROM seat s
          JOIN performance p ON p.performance_id = s.performance_id
                            AND p.title LIKE CONCAT(@marker, '-%')
         WHERE s.seat_status = 'AVAILABLE'
         GROUP BY p.performance_id, p.title) g
  LEFT JOIN (
        SELECT performance_id, COUNT(DISTINCT gap) AS distinct_steps, MIN(gap) AS step
          FROM (SELECT s.performance_id,
                       s.seat_id - LAG(s.seat_id) OVER (PARTITION BY s.performance_id
                                                        ORDER BY s.seat_id) AS gap
                  FROM seat s
                  JOIN performance p ON p.performance_id = s.performance_id
                                    AND p.title LIKE CONCAT(@marker, '-%')
                 WHERE s.seat_status = 'AVAILABLE') x
         WHERE gap IS NOT NULL
         GROUP BY performance_id) d
    ON d.performance_id = g.performance_id
 ORDER BY g.performance_id;
