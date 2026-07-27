# Grafana Explore 캡처 링크 (#496)

**SSH 터널(`-L 3000:localhost:3000`)이 떠 있어야 링크가 열린다.**

```
ssh -i /c/Users/PC/Downloads/ticket_rush_ssh.pem -N -L 9090:localhost:9090 -L 3000:localhost:3000 ubuntu@54.116.243.250
```

측정 창 4개(KST):

| 회차 | 부하 구간 | 주입 구간 |
|---|---|---|
| before `pause` | 01:58:57 ~ 02:14:38 | **02:05:32 ~ 02:07:32** |
| before `stop` | 02:16:20 ~ 02:20:32 | **02:17:48 ~ 02:18:49** |
| after `pause` | 03:08:59 ~ 03:24:45 | **03:15:34 ~ 03:17:34** |
| after `stop` | 03:24:48 ~ 03:28:59 | **03:26:15 ~ 03:27:16** |

각 링크는 시간 범위가 URL 에 박혀 있으므로 열면 바로 해당 창이 뜬다. 캡처 후 이 디렉토리에 같은 파일명으로 저장한다.

> ⚠️ **`tomcat_threads_*` 는 측정 종료 후 사라졌다.** 이 메트릭은 측정 전용 override
> (`load-test/chaos/ticket-tomcat-mbean.override.yml`)로 켠 것이고 원복했기 때문이다.
> **Prometheus 에 남은 과거 시계열은 그대로 조회되므로 위 시간 창에서는 정상적으로 보인다.**
> 지금 시각으로 범위를 옮기면 빈 그래프가 나오는데 결함이 아니다.

## graph-tomcat-busy-before-pause.png

**이 회차의 핵심 그림.** 주입 구간(02:05:32-02:07:32 KST)에 busy 가 config_max(200) 에 붙는다 = 완전 고갈

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22tomcat_threads_busy_threads%7Binstance%3D%5C%22ticket-service%3A8090%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%22tomcat_threads_config_max_threads%7Binstance%3D%5C%22ticket-service%3A8090%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785085020000%22%2C%22to%22%3A%221785086160000%22%7D%7D%7D

## graph-tomcat-busy-after-pause.png

같은 주입(03:15:34-03:17:34 KST)인데 busy 가 바닥에 붙어 있다. 서킷 open(빨간 선 1) 구간과 겹쳐 보면 인과가 한눈에 들어온다

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22tomcat_threads_busy_threads%7Binstance%3D%5C%22ticket-service%3A8090%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%22resilience4j_circuitbreaker_state%7Bname%3D%5C%22booking%5C%22%2Cstate%3D%5C%22open%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785089219000%22%2C%22to%22%3A%221785090405000%22%7D%7D%7D

## graph-gateway-5xx-before-pause.png

전파 장애의 크기. ticket-service 서버 축에는 안 잡히는 손실이 gateway 축에서만 보인다(리포트 §2.4)

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22sum%28rate%28http_server_requests_seconds_count%7Bjob%3D%5C%22gateway%5C%22%2Coutcome%3D%5C%22SERVER_ERROR%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%22sum%28rate%28http_server_requests_seconds_count%7Bjob%3D%5C%22gateway%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785085020000%22%2C%22to%22%3A%221785086160000%22%7D%7D%7D

## graph-cb-transition-after-pause.png

서킷 전이 — open 1 → half_open → closed 로 사람 개입 없이 복귀한다. not_permitted rate 가 fail-fast 로 차단한 호출 수

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22resilience4j_circuitbreaker_state%7Bname%3D%5C%22booking%5C%22%2Cstate%3D%5C%22open%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%22resilience4j_circuitbreaker_state%7Bname%3D%5C%22booking%5C%22%2Cstate%3D%5C%22half_open%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22C%22%2C%22expr%22%3A%22rate%28resilience4j_circuitbreaker_not_permitted_calls_total%7Bname%3D%5C%22booking%5C%22%7D%5B1m%5D%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785089219000%22%2C%22to%22%3A%221785090405000%22%7D%7D%7D

## graph-verify-avg-before-vs-after.png

서버 측 verify 평균. before 는 6.5초까지 치솟고 after 는 180ms 에 그친다. 두 창을 각각 캡처해 나란히 붙인다

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%221000%2A%28rate%28http_server_requests_seconds_sum%7Binstance%3D%5C%22ticket-service%3A8090%5C%22%2Curi%3D%5C%22%2Fapi%2Fv1%2Fentries%2Fverify%5C%22%7D%5B1m%5D%29%2Frate%28http_server_requests_seconds_count%7Binstance%3D%5C%22ticket-service%3A8090%5C%22%2Curi%3D%5C%22%2Fapi%2Fv1%2Fentries%2Fverify%5C%22%7D%5B1m%5D%29%29%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785085020000%22%2C%22to%22%3A%221785086160000%22%7D%7D%7D

## graph-tomcat-busy-before-stop.png

connect 실패 경로. busy 는 87 로 낮게 잡히지만 current 가 200(상한까지 확장)이고 gateway 는 연결을 거부당했다 — 15s 스크랩이 순간 피크를 놓친 것이다

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22tomcat_threads_busy_threads%7Binstance%3D%5C%22ticket-service%3A8090%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%22tomcat_threads_current_threads%7Binstance%3D%5C%22ticket-service%3A8090%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785086100000%22%2C%22to%22%3A%221785086520000%22%7D%7D%7D

## graph-tomcat-busy-after-stop.png

같은 stop 주입에서 busy 2 / current 68. 서킷이 connect 실패를 failure 축으로 잡아 열었다

http://localhost:3000/explore?orgId=1&schemaVersion=1&panes=%7B%22a%22%3A%7B%22datasource%22%3A%22prometheus%22%2C%22queries%22%3A%5B%7B%22refId%22%3A%22A%22%2C%22expr%22%3A%22tomcat_threads_busy_threads%7Binstance%3D%5C%22ticket-service%3A8090%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%2C%7B%22refId%22%3A%22B%22%2C%22expr%22%3A%22resilience4j_circuitbreaker_state%7Bname%3D%5C%22booking%5C%22%2Cstate%3D%5C%22open%5C%22%7D%22%2C%22datasource%22%3A%7B%22type%22%3A%22prometheus%22%2C%22uid%22%3A%22prometheus%22%7D%7D%5D%2C%22range%22%3A%7B%22from%22%3A%221785090168000%22%2C%22to%22%3A%221785090659000%22%7D%7D%7D
