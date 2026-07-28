---
name: apply-review
description: PR에 달린 리뷰 코멘트를 건별로 검토해 수용→수정 / 거절→사유로 대응한다. 사용자가 "PR 리뷰 반영해줘", "코드 리뷰 반영", "리뷰 코멘트 처리해줘" 등을 요청할 때 사용한다. 분류표 제시→사람 건별 결정→수용 건만 작업별 개별 커밋 후 답글.
argument-hint: '[PR번호] [--human-only|--bot-only]'
---

# /apply-review — PR 리뷰 코멘트를 하나씩 검토해 수용→수정 / 거절→사유 설명

PR에 달린 리뷰 코멘트를 받아, **사람이 건별로 수용/거절을 결정**하면 수용 건은 코드를 고치고 "어떻게/왜"를 설명하고, 거절 건은 사유를 설명한다. **메인 컨텍스트에서 실행되므로 대화 맥락을 살린다.**

## 입력
- `$ARGUMENTS` (선택) = PR 번호 + 옵션. 예: `/apply-review 249`, `/apply-review --human-only`, `/apply-review 249 --bot-only`.
- PR 번호 생략 시 현재 브랜치의 PR을 자동 감지: `gh pr view --json number -q .number`. 감지 실패하면 번호를 묻는다.
- 옵션: `--human-only`(봇 코멘트 제외) / `--bot-only`. 기본은 **봇·사람 둘 다** 처리.

## 절대 규칙
- **사람의 건별 결정·승인 없이 코드 수정·답글 전송을 하지 않는다.** 1-2단계(수집·분류)는 read-only.
- **코드 수정 전 라인 매핑을 반드시 재검증한다.** 코멘트의 `line`은 PR diff 기준이라 그대로 믿고 고치지 않는다(아래 라인 매핑 규약).
- **범위 밖 변경 금지.** 코멘트가 지적한 부분만 고친다. "겸사겸사" 리팩토링 안 한다.
- **스레드 resolve는 자동으로 하지 않는다.** (작성자가 보고 닫도록 둔다 — 필요 시 사용자가 GitHub에서 수동, 또는 명시 요청 시만.)
- **리뷰 반영은 작업(일) 단위로 개별 커밋한다. PR 리뷰 전체를 한 커밋으로 묶지 않는다.** push는 사람 명시 요청 시만. 팀 컨벤션 단일 출처는 `docs/backend-convention.md`.
- **GitHub 답글에는 이모지를 쓰지 않는다.** 그리고 답글 끝에 그 작업의 커밋 링크 `: [<짧은해시>](<커밋 URL>)`를 붙인다(거절 건은 코드 변경이 없으므로 제외). **전체를 한 번에 커밋한 뒤 같은 해시를 모든 답글에 붙이는 것은 금지** — 각 답글은 그 코멘트를 해결한 작업의 커밋을 가리켜야 한다.
- 시작 시 **현재 브랜치가 PR의 `headRefName`과 일치**하는지 확인한다. 불일치면 멈추고 안내한다(임의 체크아웃으로 미커밋 변경을 날리지 않는다).

## 작업 순서

**1단계: PR·코멘트 수집**
- PR 번호 확정(인자 또는 자동 감지) 후 메타 확인: `gh pr view <PR> --json number,headRefName,headRefOid,url`.
- 인라인 리뷰 코멘트: `gh api repos/{owner}/{repo}/pulls/<PR>/comments` (필드 `id, path, line, body, user, in_reply_to_id, diff_hunk`).
- **resolved 스레드 제외**: REST엔 resolved가 없으므로 GraphQL로 1회 조회해 해결된 스레드의 코멘트를 걸러낸다.
```
gh api graphql -f query='query($o:String!,$n:String!,$p:Int!){ repository(owner:$o,name:$n){
  pullRequest(number:$p){ reviewThreads(first:50){ nodes{ id isResolved
    comments(first:1){ nodes{ databaseId } } } } } } }' -F o=<owner> -F n=<repo> -F p=<PR>
```
  - `isResolved:true`인 스레드의 코멘트 id는 분류표에서 제외(스킵 건수만 한 줄 보고). `reviewThreads.nodes[].id`는 (필요 시) resolve용 thread node id.
- `in_reply_to_id`가 있는 코멘트는 같은 스레드의 후속 답글 → **루트 코멘트 기준으로 그룹화**해 한 항목으로 본다.
- `user.login`/봇 여부로 작성자 구분(옵션 `--human-only`/`--bot-only` 반영).

**2단계: 분류표 제시 (read-only) → ⏸ 승인 게이트**
- 미해결 코멘트 전체를 한 표로 보여준다. 각 건에 **커맨드의 추천(수용/거절) + 근거 1줄**을 단다.
  - 추천 기준: 팀 컨벤션(`docs/backend-convention.md`) 위반·🔴 버그/보안/트랜잭션은 수용 쪽, 컨벤션과 충돌하거나 범위 밖 제안은 거절 쪽. 심각도는 🔴 Must / 🟡 Should / 🔵 Nit로 표기.
- **여기서 멈추고** 사람이 건별로 결정하게 한다(예: "1,3,4 수용 · 2 거절 · 5,6 보류"). 결정 전 어떤 수정·답글도 하지 않는다.

**3단계: 수용 건을 작업(일) 단위로 묶기**
- 수용 확정된 코멘트를 **하나의 수정으로 묶이는 것끼리 "작업"으로** 그룹화한다. (예: 같은 파일·같은 주제의 다건 = 한 작업. 성격이 다른 수정 = 별도 작업.)
- 결과로 `작업 → 그 작업이 해결하는 코멘트 id 목록`을 만든다. 이 묶음이 곧 **커밋 단위**다.

**4단계: 작업별 수정 + 개별 커밋 (⚠️ 묶음 커밋 금지)**
각 작업을 순서대로 처리한다:
1. **라인 재검증**(아래 규약) → 해당 부분만 수정. 같은 파일 다건이면 **아래 라인부터**(라인 밀림 방지).
2. **"어떻게(무엇을 바꿨나) / 왜(어느 코멘트·근거로)"**를 설명한다.
3. 포맷 확인(gradle 모듈이면 `./gradlew spotlessCheck`) 후 **그 작업만 개별 커밋**한다. `commit-msg` 훅 형식 `[Type] #이슈 요약`(`[Fix]` 버그 / `[Refactor]` 구조·문구). **PR 리뷰 전체를 한 커밋으로 묶지 않는다.**
4. 커밋 직후 해시를 확보한다: `git rev-parse --short HEAD`(짧은), `git rev-parse HEAD`(전체). → 그 작업에 묶인 모든 코멘트에 이 해시를 매핑.

**5단계: 답글 전송 — 수용·거절 모두 단다 (⚠️ 거절도 빠뜨리지 않는다)**
- **모든 코멘트에 답글을 단다. 이모지 금지.**
  - **수용 건:** `<반영 설명> : [<짧은해시>](https://github.com/{owner}/{repo}/pull/<PR>/commits/<전체sha>)`
    - 같은 작업에 묶인 코멘트들은 **그 작업의 같은 커밋 해시**, 서로 다른 작업이면 서로 다른 해시.
  - **거절 건:** **정중한 거절 사유를 답글로 단다.** (코드 변경이 없으므로 커밋 링크는 붙이지 않는다.) 거절이라고 그냥 넘어가지 않는다.
- 전송(엔드포인트 검증됨): `gh api repos/{owner}/{repo}/pulls/<PR>/comments/<comment_id>/replies -f body="<답글>"`
- resolve는 하지 않는다(필요 시 사용자 수동 / 명시 요청 시만 GraphQL `resolveReviewThread`).

**6단계: 검증·마무리**
- 변경에 gradle 모듈이 포함되면 **`/test`로 실제 실행**(`.claude`/docs만이면 생략). push는 사람 명시 요청 시만.

## 라인 매핑 규약 (코드 수정 전 필수, `/review-pr`와 동일)
1. `gh pr view <PR> --json headRefName,headRefOid` → PR 브랜치/SHA
2. `git fetch origin <headRefName>`
3. 수정 대상 파일마다 `git show origin/<headRefName>:<path> | cat -n` 으로 **현재 PR 브랜치 실제 라인**을 확인하고 그 라인을 고친다. (코멘트 `line`은 diff 기준이라 신뢰하지 않는다.)

## 보고 형식
**분류 단계:** 미해결 N건 표 — `번호 | 작성자(봇/사람) | 파일 | 위치(L42 등) | 심각도 | 추천(수용/거절) | 근거` + 스킵된 resolved 건수.
**수정 단계:** 작업별로 — 묶인 코멘트 → **무엇을 / 왜 고쳤나** → 변경 `파일:라인` → **그 작업의 커밋 해시**.
**마무리:** 수용/거절 집계, `작업 → 커밋 해시 → 그 해시를 단 코멘트` 매핑, 답글 전송 여부, 다음 단계 안내. (resolve는 수동임을 알림.)
