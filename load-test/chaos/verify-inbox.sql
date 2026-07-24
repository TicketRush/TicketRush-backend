-- #347 Inbox 멱등 측정 검증 — 이중 발급·재처리 부재 확인 (read-only)
--
-- 사용법:
--   mysql -h <host> -u <user> -p ticket_rush < load-test/chaos/verify-inbox.sql
--
-- 전제:
--   * 중복 차단 건수·차단율은 이 SQL로 못 센다(inbox unique 제약으로 테이블엔 첫 1건만 남는다).
--     Prometheus에서 델타로 센다:
--       sum(increase(ticketrush_kafka_inbox_total{result="duplicate",consumer_group="<group>"}[<구간>]))
--         / sum(increase(ticketrush_kafka_inbox_total{consumer_group="<group>"}[<구간>]))
--   * inbox 건수는 재전달(offset reset) 전후로 불변이어야 한다 — 증가는 재처리 발생(멱등 깨짐)을 뜻한다.
--     inbox-redeliver.sh 실행 전에 ②를 한 번 떠서 기록해 두고 실행 후와 비교한다.

-- ① 티켓 이중 발급 검사: 동일 booking에 티켓이 2건 이상이면 즉시 검출 (기대: 0행)
SELECT booking_id, COUNT(*) AS ticket_count
FROM ticket
GROUP BY booking_id
HAVING COUNT(*) > 1;

-- ② inbox 건수 sanity: consumer_group·event_type별 COUNT (재전달 반복 전후 불변 = 재처리 0 방증)
SELECT consumer_group, event_type, COUNT(*) AS cnt
FROM inbox
GROUP BY consumer_group, event_type
ORDER BY consumer_group, event_type;
