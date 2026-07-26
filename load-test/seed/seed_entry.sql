-- ============================================================================
-- #402 입장 검표 코호트 시딩 — 스파이크 부하 측정용
--   seed_load.sql 이 만든 LOADTEST 공연을 전제로, 검표 경로에 필요한 것만 추가한다.
--     0) 검표용 ADMIN 계정 (POST /api/v1/entries/** 는 hasRole("ADMIN"))
--     1) 리셋 — 직전 회차의 USED 티켓을 UNUSED 로 되돌린다
--     2) CONFIRMED booking @ticket_count 건 — EntryVerifyUseCase 가 booking-service 를
--        동기 REST 로 조회해 bookingStatus != CONFIRMED 면 409 를 던지므로 반드시 있어야 한다
--     3) UNUSED ticket @ticket_count 건 (booking 과 1:1)
--   리셋이 앞에 들어 있어 매 회차 앞에 이 파일 하나만 돌리면 된다(seed_expired_holds.sql 선례).
--   ⚠ 로컬/부하테스트 전용 DB에서만 실행할 것 (공유/운영 DB 금지).
--
--   런북: docs/load-test-guide.md §12
--
--   ⚠ 변수를 --init-command 로 넘기지 말 것. bcrypt 해시는 '$2a$10$...' 형태라 $2·$10 이 셸에서
--     위치 매개변수로 확장돼 조용히 잘린 해시가 들어간다(시딩은 성공하고 로그인만 실패한다).
--     SET 문을 파일 앞에 붙여 stdin 으로 흘려보내면 해시가 데이터로만 지나간다:
--
--     printf "SET @i_confirm_loadtest_db=1, @ticket_count=25000, @admin_pw_hash='%s';\n" "$HASH" \
--       | cat - load-test/seed/seed_entry.sql \
--       | mysql -u root -p"$MYSQL_ROOT_PASSWORD" ticket_rush
-- ============================================================================

-- ---- 오실행 가드 -----------------------------------------------------------
-- seed_load.sql 과 동일 규약. 변수가 없으면 없는 테이블을 PREPARE 하다 ERROR 1146 으로 즉시 중단된다.
SET @stmt := IF(COALESCE(@i_confirm_loadtest_db, 0) = 1,
                'SELECT ''guard ok'' AS guard',
                'SELECT * FROM `ABORT__run_with_i_confirm_loadtest_db_eq_1`');
PREPARE guard_check FROM @stmt;
EXECUTE guard_check;
DEALLOCATE PREPARE guard_check;

-- ---- ADMIN 비밀번호 해시 가드 ----------------------------------------------
-- seed_load.sql 의 MEMBER 해시는 저장소에 커밋돼 있다. 같은 방식으로 ADMIN 해시까지 커밋하면
-- 인터넷에 열린 배포본(게이트웨이 8080)에 '해시가 공개된 관리자 계정' 을 만들게 된다. 검표 API는
-- ADMIN 권한만으로 남의 티켓을 입장 처리할 수 있으므로 MEMBER 와 위험도가 다르다.
-- 실행자가 직접 생성해 --init-command 로 주입해야만 진행된다.
--   생성 예: docker run --rm httpd:alpine htpasswd -bnBC 10 "" '<평문>' | tr -d ':\n'
--   (Spring Security 의 BCryptPasswordEncoder 는 $2a$/$2b$/$2y$ 를 모두 검증한다. 길이 60자)
SET @stmt := IF(@admin_pw_hash IS NOT NULL AND CHAR_LENGTH(@admin_pw_hash) >= 59,
                'SELECT ''admin hash ok'' AS guard',
                'SELECT * FROM `ABORT__set_admin_pw_hash_to_a_bcrypt_hash`');
PREPARE hash_check FROM @stmt;
EXECUTE hash_check;
DEALLOCATE PREPARE hash_check;

-- ---- 규모 파라미터 ---------------------------------------------------------
-- 필요 티켓 수 = 도착률 x 총 유지시간. check-in 은 UNUSED -> USED 비가역이라 티켓 1건은 1회만 쓰인다.
-- 기본 프로파일(baseline 10/s x 6m + 스파이크 60/s x 3m + 회복 10/s x 6m ≈ 19,400)에 여유 29%.
-- 뒤쪽 일부는 중복 스캔 회차(entry-duplicate-scan.js)가 쓰므로 스파이크가 도달하지 않게 남겨둔다.
SET @ticket_count = COALESCE(@ticket_count, 25000);
SET @marker       = 'LOADTEST';
SET @admin_email  = 'loadtest-admin@ticketrush.local';
-- booking 은 booking_number 프리픽스로, ticket 은 ticket_token_hash 프리픽스로 표식한다.
-- ticket 에는 마커로 쓸 컬럼이 없는데, ticket_token_hash 는 UNIQUE NOT NULL varchar(64) 이면서
-- 검표 경로에서 읽히지 않는다(QR JWT 의 tid 클레임만 쓴다) → 유일값과 정리용 범위 조건을 겸한다.
SET @bk_prefix = 'LT-E';
SET @tk_prefix = 'LOADTEST-ENTRY-';
SET SESSION cte_max_recursion_depth = 1000000;

-- ---- booking_id 를 고정 범위로 직접 지정한다 -------------------------------
-- k6 는 exec.scenario.iterationInTest 로 bookingId = ENTRY_BOOKING_ID_MIN + idx 를 만든다.
-- AUTO_INCREMENT 에 맡기면 연속성이 보장되지 않아(INSERT..SELECT + innodb_autoinc_lock_mode=2)
-- 회차마다 MIN 이 달라진다. 명시적으로 넣어 env 기본값을 상수로 고정한다.
SET @bk_base = 1000000;   -- ENTRY_BOOKING_ID_MIN = @bk_base + 1 = 1000001

-- 범위 충돌 가드 — 이 구간에 코호트 밖 booking 이 있으면 즉시 중단한다.
SET @collide = (SELECT COUNT(*) FROM booking
                 WHERE booking_id BETWEEN @bk_base + 1 AND @bk_base + @ticket_count
                   AND booking_number NOT LIKE CONCAT(@bk_prefix, '%'));
SET @stmt := IF(@collide = 0, 'SELECT ''id range ok'' AS guard',
                'SELECT * FROM `ABORT__booking_id_range_collision`');
PREPARE range_check FROM @stmt;
EXECUTE range_check;
DEALLOCATE PREPARE range_check;

-- ---- ⚠ 앱 시계와 DB 세션 시계가 다르다 -------------------------------------
-- 배포본 앱 컨테이너는 UTC 로 돌지만 MySQL 컨테이너는 system_time_zone=KST 라 세션 NOW() 가 9시간
-- 앞선다(seed_expired_holds.sql 이 실측에서 확인한 함정). 검표 경로는 시각을 판정에 쓰지 않지만
-- confirmed_at/created_at 이 9시간 미래로 박히면 증적 해석이 어긋나므로 앱 시계로 통일한다.
SET @app_now = UTC_TIMESTAMP();

-- ---- 0) 검표용 ADMIN 계정 --------------------------------------------------
-- user -> user_account 순서. user_account.user_id 는 user(id) 로 실제 FK 제약이 걸려 있다.
-- user_role='ADMIN' -> LoginUseCase.normalizeRole() 이 토큰 role 을 ADMIN 그대로 두고,
-- 게이트웨이 JwtAuthenticationFilter 가 그 값을 X-User-Role 로 주입한다.
-- 아래에서 ticket.user_id 를 이 계정으로 채우므로, QR 발급(.authenticated + 소유자 검사)과
-- verify/check-in(hasRole ADMIN)이 토큰 하나로 전부 통과한다 = k6 는 setup 로그인 1회면 된다.
INSERT INTO `user` (name, email, user_role, created_at, updated_at)
SELECT @marker, @admin_email, 'ADMIN', @app_now, @app_now FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM `user` u WHERE u.email = @admin_email);

SET @admin_id = (SELECT id FROM `user` WHERE email = @admin_email);

INSERT INTO user_account (user_id, password, created_at, updated_at)
SELECT @admin_id, @admin_pw_hash, @app_now, @app_now FROM DUAL
WHERE NOT EXISTS (SELECT 1 FROM user_account ua WHERE ua.user_id = @admin_id);

-- 해시가 바뀌면 기존 행을 갱신한다. 위 INSERT 는 idempotency 때문에 NOT EXISTS 로 막혀 있어,
-- 계정이 이미 있는 상태에서 새 해시로 재시딩하면 비밀번호가 반영되지 않는다. 그런데 시딩은
-- 성공하고 검증 쿼리의 admin_users 도 1을 찍어서, k6 setup() 의 로그인만 401 로 죽는다.
-- 회차마다 비밀번호를 새로 만드는 것이 정상 운용이므로 재시딩이 항상 권위를 갖게 한다.
UPDATE user_account
   SET password = @admin_pw_hash, updated_at = @app_now
 WHERE user_id = @admin_id AND password <> @admin_pw_hash;

-- ---- 1) 리셋 — 직전 회차를 되돌린다 ----------------------------------------
-- 삭제 후 재삽입이 아니다. booking_id 를 고정해야 ENTRY_BOOKING_ID_MIN 이 회차마다 안 바뀐다.
UPDATE ticket
   SET ticket_status = 'UNUSED', used_at = NULL, updated_at = @app_now
 WHERE ticket_token_hash LIKE CONCAT(@tk_prefix, '%')
   AND ticket_status <> 'UNUSED';

-- 예매가 CONFIRMED 가 아니면 verify 가 409 TICKET_409_001 을 던져 회차가 통째로 무효가 된다.
UPDATE booking
   SET booking_status = 'CONFIRMED', confirmed_at = @app_now, updated_at = @app_now
 WHERE booking_number LIKE CONCAT(@bk_prefix, '%')
   AND booking_status <> 'CONFIRMED';

-- ---- 2) CONFIRMED booking (idempotent) -------------------------------------
-- seat 는 건드리지 않는다. 검표 경로에 seat-service 가 없고 EntryVerifyUseCase 가 booking 에서
-- 읽는 것은 bookingStatus 뿐이다. 좌석을 SOLD 로 전이시키면 #344/#345 좌석 코호트를 오염시킨다.
-- booking.seat_id 에는 UNIQUE 도 FK 도 없으므로 LOADTEST 좌석 하나를 전 건이 공유한다.
-- 그 대가로 시드 상태는 '좌석은 AVAILABLE 인데 예매는 CONFIRMED' 라는 도메인상 불완전한 조합이
-- 된다. 측정 경로에 영향이 없음을 확인하고 의도적으로 남긴 것이며 리포트에도 명시한다.
SET @perf_id = (SELECT MIN(performance_id) FROM performance WHERE title LIKE CONCAT(@marker, '-%'));
SET @seat_id = (SELECT MIN(seat_id) FROM seat WHERE performance_id = @perf_id);

INSERT INTO booking
  (booking_id, user_id, performance_id, seat_id, booking_number, booking_status,
   confirmed_at, created_at, updated_at)
WITH RECURSIVE n(i) AS (
  SELECT 1 UNION ALL SELECT i + 1 FROM n WHERE i < @ticket_count
)
SELECT @bk_base + n.i, @admin_id, @perf_id, @seat_id,
       CONCAT(@bk_prefix, LPAD(n.i, 9, '0')), 'CONFIRMED', @app_now, @app_now, @app_now
FROM n
WHERE NOT EXISTS (   -- booking_number UNIQUE 인덱스 단건 probe
  SELECT 1 FROM booking b WHERE b.booking_number = CONCAT(@bk_prefix, LPAD(n.i, 9, '0'))
);

-- ---- 3) UNUSED ticket (booking 과 1:1, idempotent) -------------------------
-- ticket.user_id 는 QR 조회(TicketQrGetUseCase)의 소유자 검사에 쓰인다. 비면 본인 티켓도 404 다.
-- ticket.booking_id 가 UNIQUE 라 아래 NOT EXISTS 는 유니크 probe 다.
INSERT INTO ticket
  (booking_id, user_id, ticket_status, ticket_token_hash, used_at, created_at, updated_at)
SELECT b.booking_id, @admin_id, 'UNUSED',
       CONCAT(@tk_prefix, SUBSTRING(b.booking_number, CHAR_LENGTH(@bk_prefix) + 1)),
       NULL, @app_now, @app_now
FROM booking b
WHERE b.booking_number LIKE CONCAT(@bk_prefix, '%')
  AND NOT EXISTS (SELECT 1 FROM ticket t WHERE t.booking_id = b.booking_id);

-- ---- 검증 쿼리 -------------------------------------------------------------
-- booking_id_min 이 env.js 의 ENTRY_BOOKING_ID_MIN 과 같아야 하고,
-- contiguous=1, not_confirmed=0, unused=@ticket_count, owned_by_admin=@ticket_count 여야 한다.
-- 하나라도 어긋나면 측정을 진행하지 않는다.
SELECT NOW() AS db_now, @app_now AS app_now_used, @admin_id AS admin_user_id;

SELECT
  @ticket_count     AS requested,
  COUNT(*)          AS bookings,
  MIN(b.booking_id) AS booking_id_min,
  MAX(b.booking_id) AS booking_id_max,
  (MAX(b.booking_id) - MIN(b.booking_id) + 1 = COUNT(*)) AS contiguous,
  SUM(b.booking_status <> 'CONFIRMED')                   AS not_confirmed
FROM booking b
WHERE b.booking_number LIKE CONCAT(@bk_prefix, '%');

SELECT
  COUNT(*)                       AS tickets,
  SUM(ticket_status = 'UNUSED')  AS unused,
  SUM(ticket_status = 'USED')    AS used,
  SUM(user_id = @admin_id)       AS owned_by_admin
FROM ticket
WHERE ticket_token_hash LIKE CONCAT(@tk_prefix, '%');
