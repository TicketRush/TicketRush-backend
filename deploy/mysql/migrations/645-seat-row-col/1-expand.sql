-- #645 ① 확장: seat에 좌표 컬럼을 NULL 허용으로 추가한다.
--
-- 순서·롤백의 SSOT는 docs/seat-map-layout-rollout.md다. 이 파일은 기존 가동 DB에만 실행한다
-- (신규 초기화 DB는 init/001-ticket-rush-schema.sql이 이미 NOT NULL로 만든다 — 여기서 Duplicate column 실패).
--
-- NULL 허용으로 먼저 여는 이유: 구버전 앱은 이 컬럼을 모르므로 INSERT에서 빠뜨린다. NOT NULL이면 배포 전
-- 구버전의 좌석 생성이 실패한다. ddl-auto=validate는 엔티티에 없는 추가 컬럼을 무시하므로 구버전 기동에는 영향이 없다.
--
-- ALGORITHM=INSTANT는 테이블 재구성 없이 메타데이터만 바꾼다(MySQL 8.0.12+, 끝에 추가). 거절되면 INPLACE로 바꿔 실행한다.
-- MDL 대기 상한: INSTANT·INPLACE도 시작·끝에 seat의 배타 메타데이터 락이 필요하다. seat를 읽은 채 열린 트랜잭션이
-- 있으면 ALTER가 대기하고, 그 뒤의 모든 seat 조회·선점이 ALTER 뒤에 줄을 서 커넥션 풀이 고갈된다(기본값은 1년).
-- 5초 안에 못 잡으면 실패시키고, 롱 트랜잭션을 확인한 뒤 재실행한다(docs/seat-map-layout-rollout.md 3장).
SET SESSION lock_wait_timeout = 5;
ALTER TABLE seat
  ADD COLUMN seat_row int NULL,
  ADD COLUMN seat_col int NULL,
  ALGORITHM=INSTANT;
