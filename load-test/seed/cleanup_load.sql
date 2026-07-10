-- ============================================================================
-- 부하 테스트 시딩 데이터 정리 (마커 @marker / @load_email 범위만 삭제).
--   공연 계열엔 DB-level FK가 없으므로 앱 정합 역순으로 삭제: booking -> seat -> seat_layout -> performance.
--   performance를 마지막에 지워야 앞의 JOIN으로 대상 범위를 찾을 수 있다.
--   사용자 계열은 예외 — user_account.user_id 는 user(id) 로 실제 FK 제약이 걸려 있어 순서가 강제된다.
--   booking.user_id 는 FK가 아니지만 booking을 먼저 지우므로 순서상 안전하다.
--   ⚠ 로컬 전용 DB에서만 실행할 것.
-- ============================================================================
SET @marker     = 'LOADTEST';
SET @load_email = 'loadtest@ticketrush.local';

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

-- 부하테스트 전용 계정 (FK 때문에 user_account 를 먼저 지운다).
DELETE ua FROM user_account ua
JOIN `user` u ON u.id = ua.user_id
WHERE u.email = @load_email;

DELETE FROM `user` WHERE email = @load_email;
