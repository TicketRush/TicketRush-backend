<!-- 목록은 수동 관리하거나 `adr generate toc`로 갱신한다. 재생성 시 아래 "사용법 · 설치" 설명이 지워지지 않도록 주의(수동 유지 권장). -->

# 아키텍처 결정 기록 (ADR)

TicketRush의 아키텍처 결정을 번호 매긴 Markdown으로 축적하는 곳이다. 각 문서는 [Michael Nygard 템플릿](https://cognitect.com/blog/2011/11/15/documenting-architecture-decisions)(상태 / 맥락 / 결정 / 결과)을 따른다. ADR이 왜 필요한지는 [0001](0001-record-architecture-decisions.md) 참고.

## 목록

- [1. 아키텍처 결정을 기록한다](0001-record-architecture-decisions.md)
- [2. 외부용/내부용 API를 URL로 구분한다](0002-external-internal-api-url-separation.md)
- [3. 단일 공유 DB를 유지하고 서비스 규율로 데이터 경계를 강제한다](0003-shared-database-with-service-boundaries.md)
- [4. 부하 테스트 실행 토폴로지: k6는 로컬, 대상 앱은 AWS 배포본](0004-load-test-execution-topology.md)
- [5. 환불 실패를 예매 상태가 아니라 타임스탬프로 기록한다](0005-refund-state-machine-and-recovery.md)
- [6. 8 GiB 단일 인스턴스에서 컨테이너별 메모리 상한을 적용한다](0006-eight-gib-container-memory-limits.md)
- [7. 관측 스택을 측정 대상과 같은 EC2에 두고, 관측 포트는 SSH 터널로만 연다](0007-observability-stack-colocation.md)
- [8. Redis 단일 인스턴스 SPOF를 수용하고, 장애 시 전면 차단(fail-closed)한다](0008-accept-redis-spof-with-fail-closed.md)
- [9. 대기열을 게이트웨이 안의 모듈로 두고, 서버가 지시하는 폴링으로 1만 VU 유입을 제어한다](0009-virtual-waiting-room-with-server-directed-polling.md)
- [10. 1만 VU 회차의 부하 생성기를 AWS 안의 임시 EC2로 옮긴다](0010-in-aws-load-generator-for-ten-thousand-vu.md)
- [11. 내부 API 응답의 소유자 대조는 호출자가 하고, 불일치는 존재를 숨기는 코드로 접는다](0011-verify-booking-owner-at-caller.md)
- [12. PG 환불의 일시적 거절은 호출 지점에서 흡수하고, 소진되면 결정적 실패로 확정한다](0012-absorb-transient-pg-refund-rejections-at-caller.md)
- [13. 배너는 performance-service 안의 별도 bounded context로 두고, 경로는 서비스명이 아니라 리소스명을 따른다](0013-banner-as-separate-context-inside-performance-service.md)
- [14. 환불 실패 보상 신호는 발행을 보장하는 대신, 남아 있는 상태로 재발행해 복구한다](0014-recover-refund-failure-signal-by-reconciliation.md)

## 사용법

[adr-tools](https://github.com/npryce/adr-tools)로 관리한다. 레포 루트의 `.adr-dir`이 경로(`docs/adr`)를 고정하고, `templates/template.md`(한국어)가 `adr new`의 스캐폴딩 서식을 결정한다.

```bash
adr new 결제 웹훅 검증 방식을 paymentKey 재조회로 전환   # → 0002-....md 한국어 서식으로 생성
adr new -s 5 새 결정                                    # ADR 5를 supersede(대체) + 상호 링크
adr list                                               # 전체 목록
adr generate toc                                        # 목차 생성(이 README에 반영 시 위 설명 유지)
```

작성 후에는 그냥 `docs/adr/` 하위 파일을 커밋하면 된다.

## 설치

adr-tools는 bash 스크립트라 실행 환경이 필요하다(도구는 레포에 포함하지 않음, 각자 설치).

- **macOS**: `brew install adr-tools`
- **Windows (Git Bash)**: [릴리스](https://github.com/npryce/adr-tools/releases) zip 다운로드 → `src/` 전체를 `C:\Program Files\Git\usr\bin`에 복사 → 환경변수 `PAGER=less` 설정
- **Linux / WSL**: 릴리스 압축 해제 후 `src/` 디렉토리를 `PATH`에 추가

도구가 없어도 ADR 파일은 [template.md](templates/template.md) 형식에 맞춰 손으로 작성할 수 있다.
