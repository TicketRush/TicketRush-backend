# /apply-review — PR 리뷰 코멘트를 하나씩 검토해 수용→수정 / 거절→사유 설명

PR에 달린 리뷰 코멘트를 받아, **사람이 건별로 수용/거절을 결정**하면 수용 건은 코드를 고치고 "어떻게/왜"를 설명하고, 거절 건은 사유를 설명한다. **메인 컨텍스트에서 실행되므로 대화 맥락을 살린다.**

## 입력
- `$ARGUMENTS` (선택) = PR 번호 + 옵션. 예: `/apply-review 249`, `/apply-review --human-only`, `/apply-review 249 --bot-only`.
- PR 번호 생략 시 현재 브랜치의 PR을 자동 감지: `gh pr view --json number -q .number`. 감지 실패하면 번호를 묻는다.
- 옵션: `--human-only`(봇 코멘트 제외) / `--bot-only`. 기본은 **봇·사람 둘 다** 처리.

## 절대 규칙
- **사람의 건별 결정·승인 없이 코드 수정·답글 전송을 하지 않는다.** 1~2단계(수집·분류)는 read-only.
- **코드 수정 전 라인 매핑을 반드시 재검증한다.** 코멘트의 `line`은 PR diff 기준이라 그대로 믿고 고치지 않는다(아래 라인 매핑 규약).
- **범위 밖 변경 금지.** 코멘트가 지적한 부분만 고친다. "겸사겸사" 리팩토링 안 한다.
- **스레드 resolve는 자동으로 하지 않는다.** (작성자가 보고 닫도록 둔다 — 필요 시 사용자가 GitHub에서 수동, 또는 명시 요청 시만.)
- 커밋·push는 직접 하지 않고 **`/commit`에 위임**(push는 사람 명시 요청 시만). 팀 컨벤션 단일 출처는 `docs/backend-convention.md`.
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

**3단계: 수용 건 하나씩 수정**
- 수용 확정된 건만 **하나씩** 처리한다. 같은 파일에 여러 건이면 **아래 라인부터** 고친다(라인 밀림 방지).
- 각 건마다 **라인 재검증**(아래 규약) → 해당 부분만 수정 → **"어떻게(무엇을 바꿨나) / 왜(어느 코멘트·어떤 근거로)"**를 설명한다.

**4단계: 답글 (승인 후 전송)**
- 수용 건은 "반영했습니다 + 한 줄 설명", 거절 건은 "정중한 거절 사유"로 **답글 초안**을 만들어 보여준다.
- ⚠️ 답글 REST 엔드포인트는 첫 사용 시 **1건으로 실측**한 뒤 진행한다(미검증 대비). 사용자 승인 후 전송:
```
gh api repos/{owner}/{repo}/pulls/<PR>/comments/<comment_id>/replies -f body="<답글>"
```
- resolve는 하지 않는다(필요 시 사용자가 수동 / 명시 요청 시만 GraphQL `resolveReviewThread`).

**5단계: 검증·커밋 안내**
- 수정한 모듈을 **`/test`로 실제 실행** + `./gradlew spotlessCheck` 확인.
- 리뷰 반영은 **묶음 1커밋** 권장 → `/commit`에 위임. 라벨은 성격으로 추천(`[Refactor]` 컨벤션/구조, `[Fix]` 버그) — 버그 수정이 섞이면 `[Fix]` 우선. push는 사람 명시 요청 시만.

## 라인 매핑 규약 (코드 수정 전 필수, `/review-pr`와 동일)
1. `gh pr view <PR> --json headRefName,headRefOid` → PR 브랜치/SHA
2. `git fetch origin <headRefName>`
3. 수정 대상 파일마다 `git show origin/<headRefName>:<path> | cat -n` 으로 **현재 PR 브랜치 실제 라인**을 확인하고 그 라인을 고친다. (코멘트 `line`은 diff 기준이라 신뢰하지 않는다.)

## 보고 형식
**분류 단계:** 미해결 N건 표 — `번호 | 작성자(봇/사람) | 파일 | 위치(L42 등) | 심각도 | 추천(수용/거절) | 근거` + 스킵된 resolved 건수.
**수정 단계:** 건별로 — 원 코멘트 요지 → **무엇을 / 왜 고쳤나** → 변경 `파일:라인`.
**마무리:** 수용/거절 집계, 답글 전송 여부, 다음 단계(`/test`·`/commit`) 안내. (resolve는 수동임을 알림.)
