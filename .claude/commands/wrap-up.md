# /wrap-up — 세션 작업 요약 + 슬랙 전송

이번 세션에서 한 작업을 **고정 형식으로 요약**해 보여주고, 확인 후 **슬랙 채널로 전송**한다. **이 커맨드는 메인 컨텍스트에서 실행되므로, 지금까지의 대화 맥락을 살려 요약을 작성한다** — 서브에이전트로 위임하지 않는다(대화 맥락을 물려받지 못해 부정확해진다).

## 절대 규칙

- **코드·커밋·브랜치를 변경하지 않는다.** 상태 읽기와 요약·전송만 한다.
- **추측으로 단정하지 않는다.** 실제 한 일(대화 맥락 · git 기록 · diff)만 요약한다. 범위를 부풀리지 않는다.
- **비밀값(웹훅 URL)을 출력하거나 커밋되는 파일에 쓰지 않는다.** URL은 `$env:SLACK_WRAP_WEBHOOK`(gitignore된 `settings.local.json`의 `env`)에서만 읽는다.

## 입력

- `$ARGUMENTS` (선택) = 강조할 포인트·범위 힌트, 또는 전송 제어 지시.
  - 예: `결제 모듈 위주로`, `오늘 한 것만`
  - `바로전송` / `전송` 류 지시가 있으면 **3단계 확인을 생략**하고 곧장 전송한다.
  - 없으면 대화 맥락 + git 기록에서 확인한 내용으로 정한다.

## 작업 순서

### 1단계 — 사실 수집

대화 맥락을 기본으로 하고, 아래로 구체적 변경을 확인한다.
```
git branch --show-current
git status --short
git diff --stat
git log --oneline -15        # 이번 세션에 쌓인 커밋 식별
```
- 대화에서 **논의됐지만 코드로 남지 않은 결정·시도·근거**도 요약에 포함한다(직접 요약의 강점).

### 2단계 — 고정 형식 요약 작성

아래 형식(한국어)으로 작성한다. 빈 섹션은 `- 없음`으로 둔다.
```
## 한 일
- (작업 단위 불릿)

## 주요 결정·근거
- (왜 그렇게 했는지)

## 변경/커밋
- (변경 파일 요약 · 커밋 해시 — 있으면)

## 다음 할 일·미해결
- (후속 작업 · 막힌 점)
```

### 3단계 — 화면에 초안 표시 + 확인

- 작성한 요약을 **먼저 화면에 보여준다**. 슬랙은 외부 전송이므로 사용자 확인 후 전송한다(팀 draft-승인 패턴, `/pr`과 동일 철학).
- `$ARGUMENTS`에 `바로전송`/`전송` 지시가 있으면 이 확인을 생략하고 4단계로 간다.

### 4단계 — 슬랙 전송

- `$env:SLACK_WRAP_WEBHOOK`가 **비어 있으면 전송하지 않고**, "웹훅 URL 미설정(`settings.local.json`의 `env.SLACK_WRAP_WEBHOOK`)이라 전송을 스킵했다"고 안내한다(팀원 환경에서 안 깨지게).
- 값이 있으면 아래로 POST한다. 한글 깨짐 방지를 위해 **본문을 UTF-8 바이트로 인코딩**한다. (아래 예시는 **PowerShell(pwsh) 기준** — 이 레포 기본 셸이 PowerShell이다. bash 환경이라면 `curl` 등으로 동등하게 옮긴다.)
  ```powershell
  # $summary = 2단계에서 만든 요약 문자열
  $payload = @{ text = $summary } | ConvertTo-Json -Depth 4
  $bytes   = [System.Text.Encoding]::UTF8.GetBytes($payload)
  Invoke-RestMethod -Uri $env:SLACK_WRAP_WEBHOOK -Method Post `
    -ContentType 'application/json; charset=utf-8' -Body $bytes
  ```
- 전송 실패(네트워크·4xx 등) 시 그대로 보고하고 멈춘다(억지로 재시도하지 않는다).

> **확장 메모(노션 추가 시):** 이 4단계 옆에 Notion API POST 블록(`$env:NOTION_TOKEN` + DB id로 `POST https://api.notion.com/v1/pages`)만 덧붙이면 된다. 1·2단계 요약 생성부는 그대로 재사용한다.

### 5단계 — 보고

1. 작성한 요약(또는 그 요지)
2. 전송 결과: **전송됨**(채널) / **스킵**(URL 미설정) / **실패**(사유)

## 주의

- 팀 컨벤션 단일 출처는 `docs/backend-convention.md`.
- 이 커맨드는 GitHub 이슈/PR을 건드리지 않는다(그건 `/commit`·`/pr` 담당).
