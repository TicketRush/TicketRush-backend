#!/bin/sh
# 컨테이너별 메모리 시계열 샘플러 (#515).
#
# cgroup v2 를 직접 읽어 node-exporter 의 textfile collector 가 긁어갈 .prom 을 주기적으로 쓴다.
# 새 스크랩 타깃이 생기지 않으므로 prometheus.aws.yml 도 cd.yml 의 EXPECTED_TARGETS 도 그대로다.
#
# ── 왜 cAdvisor 가 아닌가 ─────────────────────────────────────────────────────
# #509 에서 cAdvisor 로 넣었다가 되돌렸다(adf21a24). 이 호스트 조합(Docker 29.1.3 + cgroup v2 +
# systemd)에서 docker factory 등록은 성공하는데 컨테이너 열거가 0건이다. v0.49.1/v0.52.1,
# --privileged, /sys/fs/cgroup 마운트를 다 시도해도 docker-<id>.scope 를 하나도 못 잡았다.
# cgroup 에는 멀쩡히 있는데 cAdvisor 가 그걸 컨테이너로 매핑하지 못하는 것이라, 그 매핑을 직접 한다.
#
# ── 왜 docker stats 가 아닌가 ────────────────────────────────────────────────
# memory.events 의 oom_kill 카운터 때문이다. docker stats 는 사용량·상한만 주고 OOM 은 알려주지
# 않는다. #509 에서 seat-service 가 죽었을 때 사후 dmesg 의 CONSTRAINT_MEMCG 로만 판정할 수 있었고
# (docker inspect 의 OOMKilled 는 재시작 뒤라 false 로 나와 오히려 오독을 부른다), 그게 이 이슈의
# 완료조건 중 하나다.
#
# ── 왜 docker.sock 을 안 쓰는가 ──────────────────────────────────────────────
# 컨테이너 이름을 얻는 가장 쉬운 길이지만, 소켓을 :ro 로 걸어도 Docker API 는 컨테이너 생성·exec 이
# 가능해 사실상 호스트 root 다. 이름은 /var/lib/docker/containers/<id>/config.v2.json 을 읽기 전용으로
# 읽어 얻는다 — 되돌린 cAdvisor 도 /var/lib/docker/:ro 를 마운트했던 선례가 있다(ADR 0007 최소 노출).
#
# ── 셸 함정 회피 ─────────────────────────────────────────────────────────────
# 중첩 따옴표를 만들지 않는다. #505 에서 호스트 샘플러의 RSS 수집이 이스케이핑 오류로 전 구간
# 실패했고, 출력의 DEAD 가 '서비스가 죽었다' 로 오독됐다. CRLF 는 .gitattributes 의 *.sh eol=lf 가 막는다
# (#348 에서 샘플러가 두 번 그렇게 죽었다 — 런북 §10.2).
set -eu

CGROUP_ROOT="${CGROUP_ROOT:-/host/cgroup/system.slice}"
DOCKER_META="${DOCKER_META:-/host/docker-containers}"
TEXTFILE_DIR="${TEXTFILE_DIR:-/textfile}"
INTERVAL="${INTERVAL:-15}"

OUT="${TEXTFILE_DIR}/container_memory.prom"
TMP="${OUT}.tmp"

# config.v2.json 에서 "Name":"/seat-service" 의 이름만 뽑는다. 파일이 크므로 첫 매치에서 끊는다.
container_name() {
  sed -n 's/.*"Name":"\/\([^"]*\)".*/\1/p' "$1" 2>/dev/null | head -1
}

sample() {
  count=0

  echo '# HELP ticketrush_container_memory_usage_bytes cgroup v2 memory.current per container (#515).'
  echo '# TYPE ticketrush_container_memory_usage_bytes gauge'
  echo '# HELP ticketrush_container_memory_limit_bytes cgroup v2 memory.max per container (#515).'
  echo '# TYPE ticketrush_container_memory_limit_bytes gauge'
  echo '# HELP ticketrush_container_memory_peak_bytes cgroup v2 memory.peak since container start (#515).'
  echo '# TYPE ticketrush_container_memory_peak_bytes gauge'
  echo '# HELP ticketrush_container_oom_kills_total cgroup v2 memory.events oom_kill count (#515).'
  echo '# TYPE ticketrush_container_oom_kills_total counter'

  for scope in "${CGROUP_ROOT}"/docker-*.scope; do
    # glob 이 아무것도 못 맞히면 패턴 자체가 남는다. 그 경우 아래 파일 검사에서 걸러진다.
    [ -r "${scope}/memory.current" ] || continue

    id="${scope##*/docker-}"
    id="${id%.scope}"

    name="$(container_name "${DOCKER_META}/${id}/config.v2.json")"
    # 이름을 못 읽으면 짧은 id 로 떨어뜨린다. 라벨을 통째로 비우면 그 컨테이너가 조용히 사라져
    # '메모리를 안 쓴다' 로 읽히는데, 그게 정확히 이 이슈가 없애려는 실패 모드다.
    [ -n "${name}" ] || name="unknown-$(echo "${id}" | cut -c1-12)"

    usage="$(cat "${scope}/memory.current" 2>/dev/null || echo '')"
    [ -n "${usage}" ] || continue
    echo "ticketrush_container_memory_usage_bytes{container=\"${name}\"} ${usage}"

    # memory.max 는 상한이 없으면 문자열 max 다. 숫자일 때만 낸다 — 상한 없는 컨테이너에 대해
    # 사용률을 계산하면 그 값이 거짓이 된다.
    limit="$(cat "${scope}/memory.max" 2>/dev/null || echo '')"
    case "${limit}" in
      '' | *[!0-9]*) : ;;
      *) echo "ticketrush_container_memory_limit_bytes{container=\"${name}\"} ${limit}" ;;
    esac

    peak="$(cat "${scope}/memory.peak" 2>/dev/null || echo '')"
    case "${peak}" in
      '' | *[!0-9]*) : ;;
      *) echo "ticketrush_container_memory_peak_bytes{container=\"${name}\"} ${peak}" ;;
    esac

    oom="$(awk '$1 == "oom_kill" { print $2; exit }' "${scope}/memory.events" 2>/dev/null || echo '')"
    case "${oom}" in
      '' | *[!0-9]*) : ;;
      *) echo "ticketrush_container_oom_kills_total{container=\"${name}\"} ${oom}" ;;
    esac

    count=$((count + 1))
  done

  # 열거 건수를 지표로 낸다. cAdvisor 는 스크랩이 성공했다는 이유로 CD 게이트(UP_COUNT)를
  # 통과하고도 컨테이너 지표가 0건이었다 — '수집기가 살아 있다' 와 '쓸 데이터가 있다' 는 다르다.
  # cd.yml 의 배포 후 검증과 런북 게이트가 이 값을 본다.
  echo '# HELP ticketrush_container_sampler_containers Containers enumerated in the last sample (#515). 0 means the sampler runs but finds nothing.'
  echo '# TYPE ticketrush_container_sampler_containers gauge'
  echo "ticketrush_container_sampler_containers ${count}"
}

echo "[container-mem-sampler] cgroup=${CGROUP_ROOT} meta=${DOCKER_META} out=${OUT} interval=${INTERVAL}s" >&2

while true; do
  # 원자적 교체. textfile collector 는 스크랩 시점에 파일을 읽으므로 제자리에 쓰면 반쯤 쓰인
  # 파일이 파싱 에러가 되고, node-exporter 는 그 스크랩 전체를 버린다.
  sample > "${TMP}"
  mv "${TMP}" "${OUT}"
  sleep "${INTERVAL}"
done
