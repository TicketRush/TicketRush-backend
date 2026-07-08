-- ============================================================================
-- 부하 테스트용 대량 데이터 시딩 (MySQL 8.0+ / 스키마 ticket_rush)
--   생성 순서: performance -> seat_layout -> seat -> (선택) booking + seat HOLD
--   전 로우는 title 마커(@marker)로 표식 -> cleanup_load.sql 로 일괄 삭제.
--   표준 재실행 절차: cleanup_load.sql 먼저 -> seed_load.sql.
--   ⚠ 로컬 전용 DB에서만 실행할 것 (공유/운영 DB 금지).
-- ============================================================================

-- ---- 규모 파라미터 (여기만 조정) -------------------------------------------
SET @perf_count  = 10;   -- 공연 수
SET @rows_per    = 20;   -- 공연당 좌석 행 수  (ponytail: 'A'~'Z' 사용, 26 이하로. 초과 시 CHAR 확장 필요)
SET @cols_per    = 30;   -- 공연당 좌석 열 수  -> 공연당 좌석 = rows*cols
SET @booking_pct = 0;    -- 좌석 중 PENDING 예매+HOLD로 선점할 비율(%) (0 = 좌석만 생성)
SET @marker      = 'LOADTEST';
-- 공연당 좌석 rows*cols, 총 좌석 = perf_count*rows*cols (예: 10*20*30 = 6,000)
-- 재귀 CTE 깊이는 max(perf_count, rows, cols)까지만 쓰므로 여유있게 상향.
SET SESSION cte_max_recursion_depth = 100000;

-- ---- 1) performance --------------------------------------------------------
-- @SQLRestriction(deleted_at IS NULL)은 앱 레벨이라 직접 INSERT엔 무관. status는 ON_SALE 직접 지정.
-- created_at/updated_at은 JPA Auditing이 채우는 값이라 직삽입 시 NOW() 명시 필수.
INSERT INTO performance
  (title, genre, show_date, show_time, duration_minutes, price, total_seats,
   performance_status, created_at, updated_at)
WITH RECURSIVE p(n) AS (
  SELECT 1 UNION ALL SELECT n + 1 FROM p WHERE n < @perf_count
)
SELECT CONCAT(@marker, '-', LPAD(p.n, 6, '0')),
       'MUSICAL', CURDATE() + INTERVAL 30 DAY, '19:00:00',
       120, 50000, @rows_per * @cols_per, 'ON_SALE', NOW(), NOW()
FROM p
WHERE NOT EXISTS (
  SELECT 1 FROM performance ex WHERE ex.title = CONCAT(@marker, '-', LPAD(p.n, 6, '0'))
);

-- ---- 2) seat_layout (공연당 1건, performance_id UNIQUE) ---------------------
INSERT INTO seat_layout (performance_id, total_rows, max_cols, created_at, updated_at)
SELECT p.performance_id, @rows_per, @cols_per, NOW(), NOW()
FROM performance p
WHERE p.title LIKE CONCAT(@marker, '-%')
  AND NOT EXISTS (SELECT 1 FROM seat_layout sl WHERE sl.performance_id = p.performance_id);

-- ---- 3) seat (공연 × 행 × 열, 전부 AVAILABLE, seat_number = 'A-1' 형식) -----
INSERT INTO seat (seat_layout_id, performance_id, seat_number, seat_status, created_at, updated_at)
WITH RECURSIVE
  r(ri) AS (SELECT 1 UNION ALL SELECT ri + 1 FROM r WHERE ri < @rows_per),
  c(ci) AS (SELECT 1 UNION ALL SELECT ci + 1 FROM c WHERE ci < @cols_per)
SELECT sl.seat_layout_id, sl.performance_id,
       CONCAT(CHAR(64 + r.ri), '-', c.ci), 'AVAILABLE', NOW(), NOW()
FROM seat_layout sl
JOIN performance p ON p.performance_id = sl.performance_id AND p.title LIKE CONCAT(@marker, '-%')
CROSS JOIN r
CROSS JOIN c
WHERE NOT EXISTS (
  SELECT 1 FROM seat s
  WHERE s.performance_id = sl.performance_id
    AND s.seat_number = CONCAT(CHAR(64 + r.ri), '-', c.ci)
);

-- ---- 4) (선택) PENDING 예매 + 좌석 HOLD 동기화 -----------------------------
-- @booking_pct=0 이면 FLOOR(...)=0 -> 0건 삽입되어 자연스럽게 skip.
-- 공연별 AVAILABLE 좌석의 앞쪽 pct% 를 PENDING booking으로 만들고 대응 좌석을 HOLD로 선점.
-- booking_number는 전역 단조 seq로 결정적 생성(UNIQUE 보장, 랜덤 금지). 재실행은 cleanup 선행 전제.
INSERT INTO booking
  (user_id, performance_id, seat_id, booking_number, booking_status, created_at, updated_at)
WITH ranked AS (
  SELECT s.seat_id, s.performance_id,
         ROW_NUMBER() OVER (PARTITION BY s.performance_id ORDER BY s.seat_id) AS rn,
         ROW_NUMBER() OVER (ORDER BY s.seat_id)                               AS gseq
  FROM seat s
  JOIN performance p ON p.performance_id = s.performance_id AND p.title LIKE CONCAT(@marker, '-%')
  WHERE s.seat_status = 'AVAILABLE'
)
SELECT 1, r.performance_id, r.seat_id,
       CONCAT('LT-', LPAD(r.gseq, 10, '0')), 'PENDING', NOW(), NOW()
FROM ranked r
WHERE r.rn <= FLOOR(@rows_per * @cols_per * @booking_pct / 100);

UPDATE seat s
JOIN booking b ON b.seat_id = s.seat_id AND b.booking_number LIKE 'LT-%'
SET s.seat_status    = 'HOLD',
    s.booking_number = b.booking_number,
    s.hold_expired_at = NOW() + INTERVAL 5 MINUTE
WHERE s.seat_status = 'AVAILABLE' AND b.booking_status = 'PENDING';

-- ---- 검증 쿼리 -------------------------------------------------------------
SELECT
  (SELECT COUNT(*) FROM performance WHERE title LIKE CONCAT(@marker, '-%')) AS performances,
  (SELECT COUNT(*) FROM seat s JOIN performance p ON p.performance_id = s.performance_id
     WHERE p.title LIKE CONCAT(@marker, '-%'))                              AS seats,
  (SELECT COUNT(*) FROM booking b JOIN performance p ON p.performance_id = b.performance_id
     WHERE p.title LIKE CONCAT(@marker, '-%'))                              AS bookings;
