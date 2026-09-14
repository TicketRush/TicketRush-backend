-- #645 ③ 제약 강화: 좌표를 NOT NULL로 잠그고 공연 안의 좌표 중복을 DB가 막게 한다.
--
-- 전제(docs/seat-map-layout-rollout.md): 구버전 앱이 전부 내려갔고, 2-backfill.sql 재실행 후 검증 SELECT가 전부 0이다.
-- 구버전이 살아 있으면 좌표 없는 INSERT가 여기서부터 실패한다. NULL 행이 남아 있으면 이 ALTER 자체가 실패한다
-- (STRICT sql_mode) — 백필 누락을 조용히 0으로 채우지 않는다.
--
-- NULL → NOT NULL은 테이블을 재구성하지만 INPLACE·LOCK=NONE으로 동시 DML을 허용한다. 좌표는 생성 후 갱신되지
-- 않으므로(Seat.seatRow updatable=false) 유니크 키는 좌석 상태 전이 쓰기에 비용을 더하지 않는다.
-- MDL 대기 상한: INSTANT·INPLACE도 시작·끝에 seat의 배타 메타데이터 락이 필요하다. seat를 읽은 채 열린 트랜잭션이
-- 있으면 ALTER가 대기하고, 그 뒤의 모든 seat 조회·선점이 ALTER 뒤에 줄을 서 커넥션 풀이 고갈된다(기본값은 1년).
-- 5초 안에 못 잡으면 실패시키고, 롱 트랜잭션을 확인한 뒤 재실행한다(docs/seat-map-layout-rollout.md 3장).
SET SESSION lock_wait_timeout = 5;
ALTER TABLE seat
  MODIFY COLUMN seat_row int NOT NULL,
  MODIFY COLUMN seat_col int NOT NULL,
  ADD UNIQUE KEY uk_seat_performance_id_seat_row_seat_col (performance_id, seat_row, seat_col),
  ALGORITHM=INPLACE, LOCK=NONE;
