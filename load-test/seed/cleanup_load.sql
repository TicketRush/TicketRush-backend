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

-- ---- #504 결제확정 파이프라인 코호트 (LTP-* / LT-P*) -------------------------
-- 이 코호트는 title 'LTP-%' 라 아래 @marker('LOADTEST-%') JOIN 에 걸리지 않으므로 따로 지운다.
-- ticket -> booking 순서는 #402 블록과 같은 이유다(ticket 에 마커 컬럼이 없어 booking 을 통해서만
-- 범위를 특정할 수 있다). 좌석은 예매와 1:1 이라 공연 JOIN 으로 정확히 잡힌다.
DELETE t FROM ticket t
JOIN booking b ON b.booking_id = t.booking_id
WHERE b.booking_number LIKE 'LT-P%';

DELETE FROM booking WHERE booking_number LIKE 'LT-P%';

DELETE s FROM seat s
JOIN performance p ON p.performance_id = s.performance_id
WHERE p.title LIKE 'LTP-%';

DELETE sl FROM seat_layout sl
JOIN performance p ON p.performance_id = sl.performance_id
WHERE p.title LIKE 'LTP-%';

DELETE FROM performance WHERE title LIKE 'LTP-%';

-- inbox 는 회차마다 새 eventId 를 쓰므로 코호트당 2N 행이 쌓인다(retention 은 기본 비활성).
-- payment-confirmed-topic 은 주입 외에 흐른 적이 없어 event_type 으로 잘라도 안전하다.
DELETE FROM inbox
 WHERE event_type = 'PaymentConfirmed'
   AND consumer_group IN ('booking-group', 'ticket-group');

-- ---- #633 payment→booking 왕복 코호트 (LTR-* / LT-R*) -----------------------
-- title 'LTR-%' 라 아래 @marker('LOADTEST-%') JOIN 에 걸리지 않으므로 따로 지운다.
-- ticket 은 지우지 않는다 — 이 코호트는 확정 이벤트를 흘리지 않아(회차가 PG 앞에서 끊긴다)
-- 티켓이 생길 일이 없다. 정상 회차라면 payment 행도 생기지 않는다(DB 쓰기가 0인 경로다).
--
-- ⚠ payment 를 booking_id 범위(BETWEEN 3000001 AND 3999999)로 지우면 안 된다. 시드가
--   booking_id 를 @bk_base+i 로 **명시 INSERT** 하는 순간 InnoDB AUTO_INCREMENT 카운터가
--   그 위로 올라가므로, 그 뒤 생성되는 **실사용 예매의 booking_id 가 그 범위 안으로 들어온다.**
--   그러면 cleanup 이 코호트와 무관한 실결제 행을 함께 지운다. #402·#504 블록이 전부
--   booking_number 마커 기준인 것이 그래서다 — 여기도 booking 을 조인해 마커로 특정한다.
--   (payment 에는 마커 컬럼이 없어 booking 을 통해서만 범위를 특정할 수 있다. 그래서
--    booking 삭제보다 **먼저** 지운다.)
--
-- 정상 회차라면 이 DELETE 는 0행이다. 시딩 사고로 코호트에 PENDING 이 섞여 PG 단계까지
-- 내려간 경우를 위한 안전망이다. 다만 그 경우에도 남는 것이 없을 가능성이 높다 —
-- **prod 프로파일 한정으로** provider=KAKAO 가 PAYMENT_PROVIDER_NOT_SUPPORTED 로 끊기고
-- 그 ErrorStatus 는 RECORDABLE_FAILURES 화이트리스트에 없어 FAILED 이력도 남지 않는다.
-- ⚠ local/test 에서는 다르다. StubPaymentApprovalClient 가 @Profile("!prod") + stub.enabled=true
--   조건의 fallback 빈이라, 스텁이 켜져 있으면 라우터가 KAKAO 를 그 스텁으로 보내 **승인이
--   성공하고 COMPLETED payment 행 + PaymentConfirmedEvent 까지 나간다.** 로컬 스모크를 돌렸다면
--   이 DELETE 가 실제로 지울 것이 있다.
DELETE p FROM payment p
JOIN booking b ON b.booking_id = p.booking_id
WHERE b.booking_number LIKE 'LT-R%';

DELETE FROM booking WHERE booking_number LIKE 'LT-R%';

DELETE s FROM seat s
JOIN performance p ON p.performance_id = s.performance_id
WHERE p.title LIKE 'LTR-%';

DELETE sl FROM seat_layout sl
JOIN performance p ON p.performance_id = sl.performance_id
WHERE p.title LIKE 'LTR-%';

DELETE FROM performance WHERE title LIKE 'LTR-%';

-- ---- #549 대기열 1만 VU 코호트 (LTQ-*) --------------------------------------
-- seed_queue_flood.sql 이 만든 공연 1건 + 좌석 1만 석 + user 1만 행. 이 코호트도 title 'LTQ-%' 라
-- 아래 @marker('LOADTEST-%') JOIN 에 안 걸리므로 따로 지운다.
-- ticket 은 지우지 않는다 — flood 는 예매를 PENDING 으로 만들고 끝나며 결제를 타지 않아 티켓이
-- 생기지 않는다. booking 은 앱이 번호를 발급해 마커가 없으므로 공연 JOIN 으로만 특정된다.
DELETE b FROM booking b
JOIN performance p ON p.performance_id = b.performance_id
WHERE p.title LIKE 'LTQ-%';

DELETE s FROM seat s
JOIN performance p ON p.performance_id = s.performance_id
WHERE p.title LIKE 'LTQ-%';

DELETE sl FROM seat_layout sl
JOIN performance p ON p.performance_id = sl.performance_id
WHERE p.title LIKE 'LTQ-%';

DELETE FROM performance WHERE title LIKE 'LTQ-%';

-- user_account 를 만들지 않았으므로(seed_queue_flood.sql 참고) FK 선행 삭제가 필요 없다.
-- email 이 UNIQUE 인덱스라 프리픽스 LIKE 가 range scan 을 탄다.
DELETE FROM `user` WHERE email LIKE 'ltq-%@ticketrush.local';

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
