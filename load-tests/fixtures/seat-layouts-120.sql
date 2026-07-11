START TRANSACTION;

SET @fixture_title = 'LOAD_TEST_SEAT_LAYOUT_120';

-- 같은 fixture를 다시 적용할 때 기존 테스트 데이터만 제거한다.
SET @old_performance_id = (
  SELECT performance_id
  FROM performance
  WHERE title = @fixture_title
  ORDER BY performance_id DESC
  LIMIT 1
);

DELETE FROM seat
WHERE performance_id = @old_performance_id;

DELETE FROM seat_layout
WHERE performance_id = @old_performance_id;

DELETE FROM performance
WHERE performance_id = @old_performance_id;

-- 부하 테스트용 공연 1개
INSERT INTO performance (
  created_at,
  updated_at,
  address,
  deleted_at,
  description,
  duration_minutes,
  genre,
  image3d_url,
  image_main_url,
  performance_status,
  performer,
  price,
  show_date,
  show_time,
  title,
  total_seats
) VALUES (
  NOW(6),
  NOW(6),
  'LOAD_TEST_ONLY',
  NULL,
  'seat-layouts load-test fixture',
  120,
  'CONCERT',
  NULL,
  NULL,
  'ON_SALE',
  'LOAD_TEST',
  10000,
  '2026-12-31',
  '20:00:00',
  @fixture_title,
  120
);

SET @performance_id = LAST_INSERT_ID();

-- 기본 배치: 10행 × 12열
INSERT INTO seat_layout (
  created_at,
  updated_at,
  max_cols,
  performance_id,
  total_rows
) VALUES (
  NOW(6),
  NOW(6),
  12,
  @performance_id,
  10
);

SET @seat_layout_id = LAST_INSERT_ID();

-- A-1부터 J-12까지 120석
INSERT INTO seat (
  created_at,
  updated_at,
  booking_number,
  hold_expired_at,
  performance_id,
  seat_layout_id,
  seat_number,
  seat_status
)
SELECT
  NOW(6),
  NOW(6),
  NULL,
  NULL,
  @performance_id,
  @seat_layout_id,
  CONCAT(CHAR(64 + row_nums.row_no), '-', col_nums.col_no),
  'AVAILABLE'
FROM (
  SELECT 1 AS row_no UNION ALL
  SELECT 2 UNION ALL
  SELECT 3 UNION ALL
  SELECT 4 UNION ALL
  SELECT 5 UNION ALL
  SELECT 6 UNION ALL
  SELECT 7 UNION ALL
  SELECT 8 UNION ALL
  SELECT 9 UNION ALL
  SELECT 10
) AS row_nums
CROSS JOIN (
  SELECT 1 AS col_no UNION ALL
  SELECT 2 UNION ALL
  SELECT 3 UNION ALL
  SELECT 4 UNION ALL
  SELECT 5 UNION ALL
  SELECT 6 UNION ALL
  SELECT 7 UNION ALL
  SELECT 8 UNION ALL
  SELECT 9 UNION ALL
  SELECT 10 UNION ALL
  SELECT 11 UNION ALL
  SELECT 12
) AS col_nums;

COMMIT;

SELECT
  p.performance_id,
  sl.seat_layout_id,
  COUNT(s.seat_id) AS seat_count
FROM performance p
JOIN seat_layout sl
  ON sl.performance_id = p.performance_id
JOIN seat s
  ON s.performance_id = p.performance_id
WHERE p.title = @fixture_title
GROUP BY p.performance_id, sl.seat_layout_id;
