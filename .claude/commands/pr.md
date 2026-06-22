# /pr — 팀 템플릿으로 Draft PR 생성 (검토 후 업로드)

작업 내용을 팀 PR 템플릿에 맞춰 **초안을 만들어 보여주고, 사용자 승인 후에만** Draft PR을 생성한다. **메인 컨텍스트에서 실행되므로 초안 제시 → 승인 대기 → 업로드를 인라인으로 진행한다.**

## 입력
- `$ARGUMENTS` (선택) = 라벨 지정·리뷰 요청 사항 힌트 등. 예: `Fix`, `테스트 항목 직접 채울게`.

## 절대 규칙
- **사용자 승인 전에는 절대 `gh pr create`를 실행하지 않는다.** 먼저 제목·본문·리뷰어 초안을 보고하고 **멈춘다.** 업로드는 사용자가 승인한 뒤에만 한다.
- **코드를 수정하지 않는다.** (본문 작성 시 임시 파일은 Bash 힙독으로 만든다)
- 반드시 **`.github/pull_request_template.md`의 양식**을 따른다.
- 작업 내역은 추측하지 말고 **git 기록 + 대화 맥락**에서 확인한 것만 적는다.
- **PR 제목은 CI 검증(`.github/workflows/pr-title.yml`)을 통과**해야 한다. 형식 `[라벨] #{이슈번호} {내용}`, 라벨 7종(`Feat` / `Fix` / `Refactor` / `Docs` / `Test` / `Chore` / `Infra`)만 허용. 다중 이슈는 `[Chore] #1, 2 ...`처럼 쉼표 구분.
- **base 브랜치는 항상 `develop`**, PR은 **Draft(`--draft`)** 로 생성한다.

## 작업 순서

**1단계: 사전 점검**
```
git branch --show-current              # 현재 작업 브랜치 (develop/main이면 중단하고 안내)
git log --oneline develop..HEAD        # 이 브랜치의 커밋 (비어 있으면 올릴 게 없음)
git status -sb                          # 미커밋/미푸시 상태
```
브랜치가 `develop`/`main`이거나 `develop..HEAD` 커밋이 없으면 PR을 만들지 말고 보고한다. 미푸시 커밋이 있으면 "push 후 생성됩니다"라고 안내한다(push는 승인 후 단계에서 처리).

**2단계: 작업 내역 수집**
```
git log develop..HEAD                   # 커밋 메시지 전문
git diff --stat develop...HEAD          # 변경 파일 요약
```
필요하면 `git diff develop...HEAD`로 핵심 의도를 파악한다. **테스트 파일**(`*Test.java`, `src/test/**`) 변경 여부를 식별해 둔다(4단계 근거).

**3단계: 이슈번호 / 라벨 결정**
- 브랜치명(`feature/248`)에서 **이슈번호**를 추출한다. `gh issue view {번호}`로 제목·내용을 참조한다(이슈 없으면 커밋 기준).
- **라벨**은 커밋 메시지 라벨 또는 변경 성격으로 7종 중 하나로 결정한다.

**4단계: 본문 작성 (`.github/pull_request_template.md` 6개 섹션)**
`cat .github/pull_request_template.md`로 읽어 섹션을 채운다:
- `🔗 관련 이슈` → `close #{이슈번호}`
- `📌 주요 변경 사항` / `🛠 상세 구현 내용` → 커밋·diff + 대화 맥락 기반
- `✅ 테스트` → **git 기반 추론**으로 초안. 변경된 테스트 파일·gradle 태스크로 방법/결과를 적되 **추론임을 명시**. 스크린샷은 비운다.
- `👀 리뷰 요청 사항` / `⚠️ 배포 시 주의사항` → 변경 성격 기반, 불명확하면 비운다.

**5단계: 리뷰어 결정 (자동 지정)**
`gh api user --jq .login`으로 작성자 username 확인 후 매핑 표에서 리뷰어를 고른다:

| 작성자 | 리뷰어 |
|---|---|
| `50h33` (소희) | `kimhyerim01` (혜림) |
| `kimhyerim01` (혜림) | `calla1102` (민주) |
| `calla1102` (민주) | `50h33` (소희) |

작성자가 표에 없으면 리뷰어를 비우고 보고한다. **assignee**는 작성자 본인 → `--assignee @me`.

**6단계: 초안 제시 후 승인 대기 (⚠️ 여기서 멈춘다)**
제목 / base / 지정 리뷰어 / assignee / 본문 전문을 사용자에게 보여주고 **"승인하시면 Draft PR을 생성합니다"** 라고 안내한 뒤 **사용자 응답을 기다린다. 이 시점에 절대 PR을 생성하지 않는다.**

**7단계: 승인 후 업로드 (승인 시에만)**
미푸시 커밋이 있으면 먼저 `git push -u origin {브랜치}` 후:
```
cat > /tmp/pr_body.md << 'EOF'
... 본문 ...
EOF
gh pr create --base develop --draft \
  --title "[라벨] #번호 내용" \
  --body-file /tmp/pr_body.md \
  --reviewer <리뷰어username> \
  --assignee @me
```

## 보고 형식
**초안 단계(6단계):** 제목 / base·Draft / 리뷰어 / assignee / 본문 전문(테스트는 추론 표시) / 승인 안내
**업로드 단계(7단계):** 생성된 PR URL·번호 / Draft 여부 / base / 리뷰어 / assignee
