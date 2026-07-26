-- ============================================================================
-- 부하 테스트 시딩 데이터 정리 (마커 @marker / @load_email 범위만 삭제).
--   공연 계열엔 DB-level FK가 없으므로 앱 정합 역순으로 삭제: booking -> seat -> seat_layout -> performance.
--   performance를 마지막에 지워야 앞의 JOIN으로 대상 범위를 찾을 수 있다.
--   사용자 계열은 예외 — user_account.user_id 는 user(id) 로 실제 FK 제약이 걸려 있어 순서가 강제된다.
--   booking.user_id 는 FK가 아니지만 booking을 먼저 지우므로 순서상 안전하다.
--   ⚠ 로컬/부하테스트 전용 DB에서만 실행할 것.
-- ============================================================================

-- ---- 오실행 가드 (seed_load.sql 과 동일) -----------------------------------
-- 삭제문이므로 오실행 피해가 시드보다 크다. 실행자가 확인 변수를 명시해야만 진행된다:
--   mysql --init-command="SET @i_confirm_loadtest_db=1" ... < cleanup_load.sql
SET @stmt := IF(COALESCE(@i_confirm_loadtest_db, 0) = 1,
                'SELECT ''guard ok'' AS guard',
                'SELECT * FROM `ABORT__run_with_i_confirm_loadtest_db_eq_1`');
PREPARE guard_check FROM @stmt;
EXECUTE guard_check;
DEALLOCATE PREPARE guard_check;

SET @marker      = 'LOADTEST';
SET @load_email  = 'loadtest@ticketrush.local';
SET @admin_email = 'loadtest-admin@ticketrush.local';

-- ---- #402 검표 코호트 -------------------------------------------------------
-- 아래 공연 계열 삭제보다 먼저 지운다. ticket 은 booking 을 통해서만 대상을 특정할 수 있고
-- (ticket 에 공연/마커 컬럼이 없다), 이 코호트의 booking 은 좌석을 공유해 공연 JOIN 만으로는
-- 범위가 겹친다. ticket -> booking 순서는 앱 정합 역순이기도 하다.
-- ticket_token_hash·booking_number 둘 다 UNIQUE 인덱스라 프리픽스 LIKE 가 range scan 을 탄다.
DELETE FROM ticket  WHERE ticket_token_hash LIKE 'LOADTEST-ENTRY-%';
DELETE FROM booking WHERE booking_number    LIKE 'LT-E%';

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
-- 검표용 ADMIN 계정(#402)도 함께 지운다. 남겨두면 인터넷에 열린 배포본에 관리자 계정이 상주한다.
DELETE ua FROM user_account ua
JOIN `user` u ON u.id = ua.user_id
WHERE u.email IN (@load_email, @admin_email);

DELETE FROM `user` WHERE email IN (@load_email, @admin_email);
