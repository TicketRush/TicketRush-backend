-- ============================================================================
-- 부하 테스트 시딩 데이터 정리 (마커 @marker 범위만 삭제).
--   DB-level FK가 없으므로 앱 정합 역순으로 삭제: booking -> seat -> seat_layout -> performance.
--   performance를 마지막에 지워야 앞의 JOIN으로 대상 범위를 찾을 수 있다.
--   ⚠ 로컬 전용 DB에서만 실행할 것.
-- ============================================================================
SET @marker = 'LOADTEST';

DELETE b FROM booking b
JOIN performance p ON p.performance_id = b.performance_id
WHERE p.title LIKE CONCAT(@marker, '-%');

DELETE s FROM seat s
JOIN performance p ON p.performance_id = s.performance_id
WHERE p.title LIKE CONCAT(@marker, '-%');

DELETE sl FROM seat_layout sl
JOIN performance p ON p.performance_id = sl.performance_id
WHERE p.title LIKE CONCAT(@marker, '-%');

DELETE FROM performance WHERE title LIKE CONCAT(@marker, '-%');
