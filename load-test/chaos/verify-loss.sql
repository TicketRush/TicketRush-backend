-- #346 장애 주입 측정 검증 — 발행 유실 집계 (read-only)
--
-- 사용법 (부하 구간 [@from, @to)를 넘긴다 — ⚠ 앱이 기록하는 created_at 기준으로.
--  프로드 컨테이너 JVM은 UTC라 created_at도 UTC-naive다. DB 세션 NOW()가 KST여도
--  구간은 UTC로 줘야 한다. 안 맞으면 expected/received가 조용히 0으로 나온다):
--   mysql -h <host> -u <user> -p \
--     --init-command="SET @from='2026-07-23 10:00:00', @to='2026-07-23 10:20:00'" \
--     ticket_rush < load-test/chaos/verify-loss.sql
--
-- 전제:
--   * booking 1건 커밋 = BookingCreatedEvent 1건 발행 기대 (BookingCreateUseCase).
--   * 수신 측은 seat-service(seat-group)의 inbox 기록으로 센다. relay 드레인 지연이 있으므로
--     inbox는 @to 이후도 포함해 실행 시점까지 센다 → ticketrush_outbox_backlog가 0으로
--     소진된 뒤 실행해야 한다. Phase A/B 구간이 시간상 겹치지 않게 할 것.
--   * 중복 수신 건수는 이 SQL로 못 센다(inbox unique 제약으로 테이블엔 첫 1건만 남는다).
--     Prometheus에서 델타로 센다:
--       increase(ticketrush_kafka_inbox_total{result="duplicate",consumer_group="seat-group"}[<구간>])

-- 파라미터 누락 가드: @from/@to가 없으면 ERROR 1048로 즉시 중단
DROP TEMPORARY TABLE IF EXISTS _verify_params;
CREATE TEMPORARY TABLE _verify_params (f DATETIME(6) NOT NULL, t DATETIME(6) NOT NULL);
INSERT INTO _verify_params VALUES (@from, @to);

-- ① 기대 이벤트 수 = 구간 내 생성된 부하 테스트 예매 수 (시드 마커 공연으로 한정)
SET @expected := (
  SELECT COUNT(*)
  FROM booking b
  JOIN performance p ON p.performance_id = b.performance_id
  WHERE p.title LIKE 'LOADTEST-%'
    AND b.created_at >= @from AND b.created_at < @to);

-- ② 실수신 수 = seat-group inbox의 BookingCreatedEvent (드레인 포함, 실행 시점까지)
SET @received := (
  SELECT COUNT(*)
  FROM inbox
  WHERE consumer_group = 'seat-group'
    AND event_type = 'BookingCreatedEvent'
    AND created_at >= @from);

SELECT @from  AS window_from,
       @to    AS window_to,
       @expected             AS expected_events,
       @received             AS received_events,
       @expected - @received AS lost_events;

-- ③ outbox 상태 분포 (outbox 모드 구간에서만 의미. DEAD>0이면 relay가 더는 안 집는다 —
--    유실이 아니라 "수동 개입 필요"로 리포트에 분리 기록하고, PENDING으로 복구 후 소진을 재확인한다)
SELECT status, COUNT(*) AS cnt
FROM outbox
WHERE aggregate_type = 'Booking'
  AND event_type = 'BookingCreatedEvent'
  AND created_at >= @from AND created_at < @to
GROUP BY status;
