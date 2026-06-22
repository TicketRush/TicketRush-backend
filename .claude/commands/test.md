# /test — 변경된 모듈의 테스트를 실제로 실행

변경된 모듈의 테스트를 **실제로 돌려** 참인 결과를 만들고, PR 템플릿 `✅ 테스트` 칸에 붙일 블록을 생성한다. **추측하지 않는다 — 오직 실행 결과만 보고한다.**

## 입력
- `$ARGUMENTS` (선택) = 모듈명/`all`/테스트 클래스 힌트. 예: `/test seat-service`, `/test all`, `/test seat-service --tests "*SeatHoldTest"`.
- 없으면 **변경된 모듈만** 자동 감지해 실행한다.

## 절대 규칙
- **코드를 수정하지 않는다.** 테스트 실행·결과 보고만 한다. (테스트 코드 작성은 `/test`의 일이 아님 — 구현 단계에서 함)
- **결과를 추측하지 않는다.** gradle을 실제로 실행한 출력만 보고한다. 실패하면 그대로 보고하고 멈춘다(우회·무시 금지).
- **커밋·push 하지 않는다.**
- 모듈 목록은 **`settings.gradle`을 신뢰**한다(CLAUDE.md 목록이 아님 — `order-service`는 실제로 없음).

## 작업 순서

**1단계: 대상 모듈 결정**
- `$ARGUMENTS`에 모듈명이 있으면 그 모듈. `all`이면 전체.
- 없으면 변경 파일에서 자동 감지:
```
git diff --name-only develop...HEAD     # 브랜치 변경
git status --porcelain                   # 미커밋 변경
```
  - 각 경로의 **첫 세그먼트 = 모듈명** 후보 → `settings.gradle`에 `include`된 모듈명과 **교집합**만 채택(`.claude`, `docs`, `.github`, `config`, `gradle` 등 비모듈 제외).
  - **`common` 변경이 포함되면 전체 테스트로 승격**한다. (common은 다른 모듈이 의존 + test-fixtures 제공 → 영향 범위 넓음.) 그 이유를 한 줄로 보고한다.
  - 채택된 모듈이 없으면(예: `.claude`/`docs`만 변경) **"테스트 대상 코드 변경 없음"**을 보고하고 종료한다.

**2단계: 테스트 실행**
- 모듈별(여러 개면 한 번에): `./gradlew :모듈A:test :모듈B:test --console=plain`
- 전체/common 승격: `./gradlew test --console=plain`
- 클래스 힌트 있으면 `--tests "..."` 추가.

**3단계: 결과 수집 (요약만)**
- gradle 종료 코드 + 모듈별 통과/실패 수를 파악한다.
- **실패 시**: 실패한 테스트명과 핵심 메시지만 추출한다 (필요하면 `<모듈>/build/test-results/test/*.xml` 또는 콘솔의 `FAILED` 라인). **전체 로그를 그대로 붙이지 않는다.**
- 리포트 위치 안내: HTML `<모듈>/build/reports/tests/test/index.html`.

**4단계: PR 템플릿용 블록 출력**
실제 결과로 아래 형태를 만들어 보여준다(사람이 PR `✅ 테스트` 칸에 붙임):
```
### 테스트 방법
- ./gradlew :seat-service:test :payment-service:test

### 테스트 결과
- seat-service: 42 통과 / 0 실패 ✅
- payment-service: 18 통과 / 1 실패 ❌ (SeatHoldTest.expireAfterTtl: expected <X> but was <Y>)
```

## 보고 형식
1. 대상 모듈과 선정 근거(변경 감지 / 인자 / common 승격) 1줄
2. 실행한 gradle 명령
3. 모듈별 통과·실패 요약 (+ 실패 시 실패 케이스)
4. PR `✅ 테스트` 칸에 붙일 블록

> ⚠️ 테스트 출력은 크다. 큰 로그가 메인 컨텍스트를 차지해 거슬리면, 이 커맨드를 **테스트 전용 에이전트로 승격**해 요약만 반환하게 바꾸는 것을 고려한다.
