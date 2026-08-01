-- =============================================================================
-- #533 outbox 릴레이 지연 — published_at - created_at
--
-- 실행:
--   cat query.sql | tr -d '\r' \
--     | ssh -i <key> ubuntu@<EC2> \
--         'docker exec -i ticketrush-mysql sh -c '"'"'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" ticket_rush'"'"''
--
-- ⚠ 시각 리터럴은 UTC 다. MySQL 서버 프로세스는 KST 이지만 created_at/published_at 은
--   JPA auditing 이 찍으므로 JVM TZ(=UTC)를 따른다. KST 로 쓰면 0 행이 나온다.
--   (실제로 처음에 그렇게 틀렸다 — metadata.txt 의 TZ_* 항목 참고)
--
-- ⚠ retention 이 publishedAt < now-72h 인 SENT 를 지운다(retention-hours: 72).
--   대조하려는 회차의 행이 아직 살아 있는지 [0] 으로 먼저 확인한다.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- [0] 시간대·데이터 생존 확인 — 이걸 먼저 본다
-- -----------------------------------------------------------------------------
SELECT @@global.time_zone AS g_tz, @@session.time_zone AS s_tz,
       NOW() AS db_now, UTC_TIMESTAMP() AS db_utc;

SELECT aggregate_type, status, COUNT(*) AS n,
       MIN(created_at)   AS min_created,   MAX(created_at)   AS max_created,
       MIN(published_at) AS min_published, MAX(published_at) AS max_published
FROM outbox
GROUP BY aggregate_type, status
ORDER BY aggregate_type, status;

-- 창을 특정하기 전 탐색용 — 부하 회차는 시간대별 건수 피크로 드러난다
SELECT DATE_FORMAT(created_at, '%m-%d %H') AS hr, aggregate_type, COUNT(*) AS n,
       ROUND(AVG(TIMESTAMPDIFF(MICROSECOND, created_at, published_at)) / 1000.0, 1) AS avg_ms
FROM outbox
WHERE status = 'SENT'
  AND created_at >= '2026-07-30 00:00:00' AND created_at < '2026-07-31 00:00:00'
GROUP BY hr, aggregate_type
ORDER BY hr, aggregate_type;

-- -----------------------------------------------------------------------------
-- [1] 요약 통계 — #528 RUN_A 창 (UTC 09:46:19 ~ 10:17:52)
-- -----------------------------------------------------------------------------
SELECT CONCAT('RUN_A-', aggregate_type) AS win, COUNT(*) AS n,
       ROUND(MIN(TIMESTAMPDIFF(MICROSECOND, created_at, published_at)) / 1000.0, 1) AS min_ms,
       ROUND(AVG(TIMESTAMPDIFF(MICROSECOND, created_at, published_at)) / 1000.0, 1) AS avg_ms,
       ROUND(MAX(TIMESTAMPDIFF(MICROSECOND, created_at, published_at)) / 1000.0, 1) AS max_ms,
       ROUND(STDDEV(TIMESTAMPDIFF(MICROSECOND, created_at, published_at)) / 1000.0, 1) AS sd_ms,
       ROUND((MAX(TIMESTAMPDIFF(MICROSECOND, created_at, published_at))
            - MIN(TIMESTAMPDIFF(MICROSECOND, created_at, published_at))) / 1000.0, 1) AS span_ms
FROM outbox
WHERE status = 'SENT'
  AND created_at >= '2026-07-30 09:46:19' AND created_at < '2026-07-30 10:17:52'
GROUP BY aggregate_type;

-- -----------------------------------------------------------------------------
-- [2] 분포 — 500ms 버킷. 0~5s 에 평평하면 균등분포(= 폴링이 지배항)
-- -----------------------------------------------------------------------------
SELECT FLOOR(TIMESTAMPDIFF(MICROSECOND, created_at, published_at) / 500000) * 0.5 AS bucket_s,
       COUNT(*) AS n
FROM outbox
WHERE aggregate_type = 'Booking' AND status = 'SENT'
  AND created_at >= '2026-07-30 09:46:19' AND created_at < '2026-07-30 10:17:52'
GROUP BY bucket_s
ORDER BY bucket_s;

-- -----------------------------------------------------------------------------
-- [3] 퍼센타일 — 균등분포면 p50 ~ 2.5s, p95 ~ 4.75s 여야 한다
-- -----------------------------------------------------------------------------
SELECT ROUND(MAX(CASE WHEN pct <= 0.50 THEN delay_ms END), 1) AS p50_ms,
       ROUND(MAX(CASE WHEN pct <= 0.90 THEN delay_ms END), 1) AS p90_ms,
       ROUND(MAX(CASE WHEN pct <= 0.95 THEN delay_ms END), 1) AS p95_ms,
       ROUND(MAX(CASE WHEN pct <= 0.99 THEN delay_ms END), 1) AS p99_ms
FROM (
  SELECT TIMESTAMPDIFF(MICROSECOND, created_at, published_at) / 1000.0 AS delay_ms,
         PERCENT_RANK() OVER (ORDER BY TIMESTAMPDIFF(MICROSECOND, created_at, published_at)) AS pct
  FROM outbox
  WHERE aggregate_type = 'Booking' AND status = 'SENT'
    AND created_at >= '2026-07-30 09:46:19' AND created_at < '2026-07-30 10:17:52'
) t;

-- -----------------------------------------------------------------------------
-- [4] 버스트 구간 (#528 RUN_B2, UTC 10:28:48 ~ 10:37:24)
--     2,000건 일괄 만료를 주입한 구간이다. outbox 가 이때도 5~6초 안에 처리했는지 본다.
-- -----------------------------------------------------------------------------
SELECT CONCAT('RUN_B2-', aggregate_type) AS win, COUNT(*) AS n,
       ROUND(MIN(TIMESTAMPDIFF(MICROSECOND, created_at, published_at)) / 1000.0, 1) AS min_ms,
       ROUND(AVG(TIMESTAMPDIFF(MICROSECOND, created_at, published_at)) / 1000.0, 1) AS avg_ms,
       ROUND(MAX(TIMESTAMPDIFF(MICROSECOND, created_at, published_at)) / 1000.0, 1) AS max_ms
FROM outbox
WHERE status = 'SENT'
  AND created_at >= '2026-07-30 10:28:48' AND created_at < '2026-07-30 10:37:24'
GROUP BY aggregate_type;
