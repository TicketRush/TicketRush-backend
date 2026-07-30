-- #529 인덱스 교체 증적. 실행: 2026-07-30T10:38:32Z
SELECT '=== BEFORE: SHOW INDEX FROM seat ===' AS section;
SHOW INDEX FROM seat;
SELECT '=== BEFORE: 행수 ===' AS section;
SELECT COUNT(*) AS seat_rows FROM seat;
SELECT '=== BEFORE: EXPLAIN (집계 쿼리, perf 15) ===' AS section;
EXPLAIN SELECT COUNT(*),
  COUNT(CASE WHEN seat_status='AVAILABLE' OR (seat_status='HOLD' AND hold_expired_at <= UTC_TIMESTAMP()) THEN 1 END),
  COUNT(CASE WHEN seat_status='SOLD' THEN 1 END),
  COUNT(CASE WHEN seat_status='HOLD' AND hold_expired_at > UTC_TIMESTAMP() THEN 1 END)
FROM seat WHERE performance_id = 15;
SELECT '=== DDL 적용 ===' AS section;
ALTER TABLE seat
  ADD INDEX idx_seat_performance_id_status_hold_expired_at
    (performance_id, seat_status, hold_expired_at),
  ALGORITHM=INPLACE, LOCK=NONE;
ALTER TABLE seat DROP INDEX idx_seat_performance_id, ALGORITHM=INPLACE, LOCK=NONE;
SELECT '=== AFTER: SHOW INDEX FROM seat ===' AS section;
SHOW INDEX FROM seat;
SELECT '=== AFTER: EXPLAIN (집계 쿼리, perf 15) ===' AS section;
EXPLAIN SELECT COUNT(*),
  COUNT(CASE WHEN seat_status='AVAILABLE' OR (seat_status='HOLD' AND hold_expired_at <= UTC_TIMESTAMP()) THEN 1 END),
  COUNT(CASE WHEN seat_status='SOLD' THEN 1 END),
  COUNT(CASE WHEN seat_status='HOLD' AND hold_expired_at > UTC_TIMESTAMP() THEN 1 END)
FROM seat WHERE performance_id = 15;
SELECT '=== AFTER: EXPLAIN (좌석맵 조회 — 제거한 인덱스의 다른 사용처) ===' AS section;
EXPLAIN SELECT seat_id, seat_layout_id, seat_number, seat_status, hold_expired_at
FROM seat WHERE performance_id = 15;
