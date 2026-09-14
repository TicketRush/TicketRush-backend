-- #645 롤백: 3-contract.sql을 되돌려 구버전 앱의 좌표 없는 INSERT가 다시 성공하게 한다.
--
-- 3-contract.sql을 적용한 뒤 구버전으로 되돌릴 때만, 구버전 기동 **전**에 실행한다(1·2단계만 한 상태면 불필요).
-- 백필한 좌표 값은 지우지 않는다 — 재전환 시 2-backfill.sql이 NULL 행만 다시 채우면 된다.
-- MDL 대기 상한: INSTANT·INPLACE도 시작·끝에 seat의 배타 메타데이터 락이 필요하다. seat를 읽은 채 열린 트랜잭션이
-- 있으면 ALTER가 대기하고, 그 뒤의 모든 seat 조회·선점이 ALTER 뒤에 줄을 서 커넥션 풀이 고갈된다(기본값은 1년).
-- 5초 안에 못 잡으면 실패시키고, 롱 트랜잭션을 확인한 뒤 재실행한다(docs/seat-map-layout-rollout.md 3장).
SET SESSION lock_wait_timeout = 5;
ALTER TABLE seat
  DROP INDEX uk_seat_performance_id_seat_row_seat_col,
  MODIFY COLUMN seat_row int NULL,
  MODIFY COLUMN seat_col int NULL,
  ALGORITHM=INPLACE, LOCK=NONE;

-- 컬럼까지 완전히 걷어내야 할 때만 별도 승인 후 실행한다(좌표 데이터가 사라진다).
-- ALTER TABLE seat DROP COLUMN seat_row, DROP COLUMN seat_col, ALGORITHM=INPLACE, LOCK=NONE;
