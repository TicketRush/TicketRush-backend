# 🤖 AI 개발 워크플로우 가이드 (Claude Code)

본 문서는 우리 프로젝트에 도입한 **Claude Code 기반 AI 통제 개발 환경**의 사용법을 정리한 가이드입니다.
목표는 "AI로 코드를 짰다"가 아니라 **"AI가 안전하게 일하도록 통제된 프로세스를 갖췄다"** 입니다.

세 가지 축으로 구성됩니다.

1. **Plan-First + Human Approval** — 코드 수정 전 계획·리스크를 정리하고 사람이 승인
2. **역할 분리 서브에이전트** — 조사 / 계획 / 검증 / 이슈 작성 / PR 작성을 독립된 에이전트로 분리
3. **작업 로깅 훅** — 프롬프트·도구 실행 이력을 기록해 재현·추적 가능

---

## 1. 디렉토리 구조

```
.claude/
├── settings.json          # 플랜모드 기본값 + 로깅 훅 등록 (팀 공유, git 커밋)
├── settings.local.json    # 개인 권한 화이트리스트 (gitignore, 개인별)
├── agents/                # 역할별 서브에이전트 정의
│   ├── researcher.md
│   ├── planner.md
│   ├── reviewer.md
│   ├── issue-creator.md
│   └── pr-creator.md
├── commands/              # 슬래시 커맨드
│   ├── review-pr.md
│   ├── dev-cycle.md       # 이슈 기반 개발 사이클 자동 진행
│   └── commit.md          # 팀 형식으로 커밋 (브랜치명→이슈번호 자동)
├── hooks/
│   └── log.ps1            # 프롬프트/도구 실행 로깅 스크립트
└── logs/                  # 실행 로그 (gitignore, 런타임 데이터)
    ├── prompt.jsonl
    └── tools.jsonl
```

> `.claude/logs/`는 `.gitignore`의 `logs` 패턴으로 자동 제외되며, `settings.local.json`(개인 권한)도 커밋되지 않습니다.

---

## 2. 에이전트 vs 커맨드 — 호출 방식의 차이

| 구분                 | 정의 위치                   | 호출 방식                                           | 성격                           |
|--------------------|-------------------------|-------------------------------------------------|------------------------------|
| **에이전트 (agents)**  | `.claude/agents/*.md`   | "○○ 에이전트로 ~해줘"라고 지시하거나, Claude가 작업 성격에 맞춰 자동 위임 | 역할을 가진 일꾼. **독립된 컨텍스트**에서 실행 |
| **커맨드 (commands)** | `.claude/commands/*.md` | 입력창에 `/커맨드명 [인자]` 직접 입력                         | 정해진 절차를 실행하는 명령              |

---

## 3. 서브에이전트 활용법

작업 흐름 순서대로 사용하면 자연스럽습니다: **조사 → 계획 → (승인 → 구현) → 검증 → 이슈 → PR**

### 3.1. `researcher` — 조사 (구현 전 현황 파악)

새 작업 전 "지금 코드/스펙이 어떻게 돼 있지?"를 확인할 때.

* 코드를 수정하지 않고, 근거(`파일:라인` / 출처 URL)와 함께 사실만 보고합니다.
* 예시:
    * *"researcher로 ticket-service 발급 트랜잭션 흐름이 지금 어떻게 돼 있는지 조사해줘"*
    * *"researcher로 Spring `@Transactional` 전파 옵션 레퍼런스 찾아줘"*

### 3.2. `planner` — 계획 (구현 직전)

"어떻게 구현할지 단계와 리스크를 먼저 보고 싶다"일 때.

* 변경 대상 파일 · 단계별 계획 · 리스크/영향 범위 · 롤백 방안 · **사람이 결정할 사항**을 산출합니다.
* 이것이 곧 사람이 승인할 "플랜"입니다. (Plan Mode와 짝을 이룸)
* 예시: *"planner로 좌석 선점 만료 스케줄러 추가 작업 계획 세워줘"*

### 3.3. `reviewer` — 셀프 검증 (구현 후, PR 전)

커밋/PR 전에 로컬 변경을 시니어 시각으로 점검할 때.

* `git diff develop...HEAD` 등을 검토하여 🔴 Must / 🟡 Should / 🔵 Nit 로 보고합니다.
* **Edit/Write 권한이 없어 직접 수정할 수 없습니다.** (검증 독립성을 구조적으로 강제)
* 예시: *"reviewer로 지금까지 변경한 거 리뷰해줘"*

### 3.4. `issue-creator` — 이슈 생성

작업 내역을 바탕으로 팀 이슈 템플릿에 맞춰 GitHub 이슈를 생성할 때.

* `git log/diff/status`로 작업을 파악하고 `.github/ISSUE_TEMPLATE/`에서 알맞은 템플릿을 골라 채운 뒤 `gh issue create`까지
  수행합니다.
* 제목은 커밋 규칙과 동일한 `[라벨] 제목` 형식으로 작성합니다.
* 예시: *"issue-creator로 이번 작업 이슈 만들어줘"*

### 3.5. `pr-creator` — PR 생성 (구현·검증 후)

작업 내역을 바탕으로 팀 PR 템플릿에 맞춰 GitHub PR을 올릴 때.

* `git log/diff`로 작업을 파악하고 `.github/pull_request_template.md`의 6개 섹션을 채웁니다. 테스트 항목은 변경된 테스트
  파일·gradle 태스크로 **추론해 초안**을 적습니다(사람이 검토·수정).
* 제목은 CI 검증(`.github/workflows/pr-title.yml`)을 통과하도록 `[라벨] #이슈번호 내용` 형식으로 작성하고, **base는 항상 `develop`
  **, **Draft**로 올립니다.
* **리뷰어는 컨벤션 표에 따라 자동 지정**되고(작성자→리뷰어), **assignee는 본인**(`@me`)으로 지정됩니다.
* ⚠️ **사용자 승인 전에는 절대 PR을 생성하지 않습니다.** 먼저 제목·본문·리뷰어 초안을 보고하고 멈추며, *"올려줘 / 승인"* 후에만 `gh pr create`를
  실행합니다.
* 예시: *"pr-creator로 PR 초안 만들어줘"* → 초안 확인 → *"이대로 올려줘"*

> 💡 **호출 팁:** 에이전트 이름을 명시(`researcher로`, `planner한테`)하면 해당 에이전트로 위임됩니다. 그냥 "조사해줘 / 계획 세워줘"라고만 해도
> Claude가 description을 보고 알맞은 에이전트를 자동으로 띄울 수 있습니다.

---

## 4. 커맨드 활용법 — `/review-pr`

GitHub에 올라간 **PR을 리뷰**하는 슬래시 커맨드입니다.

* 사용법: `/review-pr <PR번호>` (예: `/review-pr 248`)
* `gh pr view/diff`로 PR을 분석하고, **PR 브랜치 기준 실제 라인 번호**까지 검증하여 코멘트 제안 목록과 "Finish your review" 문구를
  생성합니다.
* 제안 목록을 보고 *"1, 3번 달아줘"* 라고 하면 실제 PR 코멘트를 작성합니다.

---

## 5. `reviewer` 에이전트 vs `/review-pr` — 언제 무엇을

| 구분 | `reviewer` 에이전트                         | `/review-pr` 커맨드          |
|----|-----------------------------------------|---------------------------|
| 시점 | **PR 올리기 전** (로컬)                       | **PR 올린 후** (GitHub)      |
| 대상 | 로컬 working tree / `develop...HEAD` diff | 원격 PR (`gh pr diff`)      |
| 결과 | 심각도별 지적 보고                              | GitHub 코멘트 제안 + 실제 코멘트 작성 |
| 용도 | 내가 먼저 하는 셀프 점검                          | 팀 PR 리뷰 프로세스              |

---

## 6. 권장 작업 사이클

```
researcher (조사)
   → planner 초안 → /grill-me 대화 → 계획 확정
      → ⏸ 사람 승인 (Plan Mode)
         → issue-creator (이슈 생성)  →  feature/{이슈번호} 브랜치 분기
            → 구현
               → reviewer (셀프 검증)
                  → 커밋  [Type] #이슈번호 요약
                     → pr-creator (PR 초안 → ⏸ 승인 → Draft PR 생성)
                        → /review-pr (PR 리뷰)
```

> 팀 규칙상 **이슈 생성 → 이슈 번호 기반 브랜치 → 커밋 → PR** 순서이므로, `issue-creator`로 이슈를 먼저 만든 뒤 `feature/{이슈번호}` 브랜치를
> 분기하고, 그 브랜치 위에서 구현·검증·커밋을 진행합니다. 조사(`researcher`)·계획(`planner`)은 이슈 작성 전 사전 파악 단계에서 활용하거나, 브랜치 분기 이후
> 구현 직전에 활용해도 됩니다.

### 6.1. `/dev-cycle` — 사이클 자동 진행

위 사이클을 **단계마다 직접 지시하지 않아도** 한 번에 시작할 수 있습니다.

* **트리거(둘 다 동작):**
    * 자연어 — *"이슈 1번 개발 진행하자"* (이슈 번호 + 개발 시작 의도) → Claude가 `/dev-cycle 1`로 매핑 (`CLAUDE.md` 규칙)
    * 커맨드 — `/dev-cycle 1` 직접 입력
* **이슈는 이미 있다고 가정**합니다. 번호로 `gh issue view`만 하고 `feature/{번호}` 브랜치를 따며, **issue-creator(이슈 생성)는 건너뜁니다.**
* **계획 단계는 대화형**입니다: `planner`가 초안을 낸 뒤 **`/grill-me`** 로 결정사항·엣지케이스를 캐물어 계획을 함께 확정합니다.
* **완전 무인 자동이 아닙니다.** 두 곳에서 **반드시 멈춰 사람 승인**을 받습니다:
    * ⏸ **플랜 승인** — 계획 확정 후, 구현 전
    * ⏸ **PR 업로드 승인** — pr-creator 초안 후, Draft PR 생성 전
* 그 사이 구간(조사 / 브랜치·구현 / 셀프 검증 / 커밋)은 자동으로 이어집니다.

> ⚙️ **사전 준비 — `/grill-me` 설치:** 계획 단계의 grill-me는 외부 스킬입니다. 한 번만 설치하면 됩니다:
> `npx skills@latest add mattpocock/skills` (설치 후 `/setup-matt-pocock-skills`로 초기 설정).
> 미설치 상태면 `/dev-cycle`은 grill-me 대신 일반 질문(AskUserQuestion)으로 계획을 캐물어 진행합니다.

---

## 7. 로깅 훅

`.claude/hooks/log.ps1`이 다음 시점에 실행되어 작업 이력을 JSONL로 남깁니다.

| 훅 이벤트                                                           | 기록 대상          | 로그 파일                       |
|-----------------------------------------------------------------|----------------|-----------------------------|
| `UserPromptSubmit`                                              | 사용자가 입력한 프롬프트  | `.claude/logs/prompt.jsonl` |
| `PreToolUse` (`Bash`/`Edit`/`Write`/`MultiEdit`/`NotebookEdit`) | 실행 직전 도구 호출 정보 | `.claude/logs/tools.jsonl`  |

* 각 레코드에는 `ts`(ISO-8601 타임스탬프), `kind`, `event`(세션 ID, cwd, `tool_name`, `tool_input`, 프롬프트 텍스트 등)가
  담겨 **재현·디버깅·감사**가 가능합니다.
* stdin을 UTF-8로 디코딩하여 한글이 깨지지 않도록 처리합니다.
* 로그는 런타임 데이터이므로 커밋되지 않습니다(`.gitignore`).

### 7.1. 작업 이력, 이렇게 활용한다

로그에는 **언제 / 어떤 프롬프트로 / 어떤 도구·명령을 실행했는지**가 시간순으로 쌓이므로, 사후에 다음처럼 활용할 수 있습니다.

| 활용              | 내용                                                                |
|-----------------|-------------------------------------------------------------------|
| **작업 재현·감사**    | "이 변경을 왜·어떻게 했지?"를 프롬프트→도구 호출 시퀀스로 사후 재구성. AI가 한 일을 사람이 추적·검증     |
| **문제 발생 시 추적**  | 빌드/테스트가 깨졌을 때 **직전에 실행된 도구 호출 순서**를 확인해 원인 구간을 빠르게 좁힘             |
| **위험 명령 점검**    | `git push --force`, `rm` 등 민감한 명령이 실제로 실행됐는지 사후에 검색해 확인(보안·규정 감사) |
| **회고·통계**       | 세션별 프롬프트 수, 자주 돌린 gradle 태스크·테스트 명령 분포를 집계해 **AI 도입 효과를 정량 리포트**로 |
| **온보딩 자료**      | 실제 작업 흐름(프롬프트→명령)을 신규 팀원이 따라가며 우리 개발 패턴을 학습                       |
| **PR/이슈 본문 보조** | 본문을 채울 때 실제로 돌린 테스트·명령 이력을 근거로 참고(주 출처는 git, 로그는 보조)              |

**활용 예시** — JSONL이라 PowerShell `ConvertFrom-Json`으로 바로 질의할 수 있습니다.

```powershell
# (1) 실행한 Bash 명령을 시간순으로 추출
Get-Content .claude/logs/tools.jsonl | ForEach-Object { $_ | ConvertFrom-Json } |
  Where-Object { $_.event.tool_name -eq 'Bash' } |
  ForEach-Object { "$($_.ts)  $($_.event.tool_input.command)" }

# (2) 세션별 프롬프트 개수 집계 (회고용)
Get-Content .claude/logs/prompt.jsonl | ForEach-Object { $_ | ConvertFrom-Json } |
  Group-Object { $_.event.session_id } | Select-Object Count, Name

# (3) force push 등 위험 명령이 있었는지 점검
Select-String -Path .claude/logs/tools.jsonl -Pattern 'push --force|rm -rf'
```

> 로그는 개인 로컬에만 쌓이고 커밋되지 않으므로, 팀 차원의 통계·감사가 필요하면 각자 위 질의 결과를 공유하거나 별도 수집 절차를 두면 됩니다.
