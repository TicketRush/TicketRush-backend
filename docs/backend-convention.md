# 📑 백엔드 개발 컨벤션 가이드

## 1. 협업 및 깃허브(GitHub) 컨벤션

### 👥 코드 리뷰

* 코드 리뷰는 **선택(권장)** 사항이며 **머지 필수 조건이 아닙니다.** 리뷰어가 필요하면 사람이 직접 지정합니다.
* `/pr` 자동화는 **리뷰어를 자동 지정하지 않습니다.**(요청 시에만 부착)

### 💬 Discussion

* 의논해야 할 모든 주요 사항은 **GitHub Discussion**에 업로드하여 히스토리를 남깁니다.

### 🚩 이슈(Issue) 규칙

* **이슈 제목:** `[모듈] {내용}`
* 예: `[회원] 로그인 기능 구현`
* 예: `[공통] IDE 코드 스타일 정리`


* **책임 할당:** 자신의 담당이 아닌 다른 사람의 부분에서 수정이 필요한 사항을 발견하면, 발견한 사람이 직접 해당 담당자를 위한 이슈를 생성해 줍니다.

### 🌿 브랜치(Branch) 규칙

* **브랜치 단위:**
* `main`: 배포 가능한 안정적인 버전
* `develop`: 다음 출시를 위해 개발 중인 통합 브랜치
* `feature`: 새로운 기능 개발
* `fix`: 버그 수정 (※ `bug` 대신 `fix`로 통일)
* `refactor`: 코드 리팩토링
* `infra`: 인프라 관련 설정
* `chore`: 빌드 업무, 패키지 매니저 설정 등
* `docs`: 문서 수정
* `test`: 테스트 코드 추가 및 수정


* **브랜치 생성 방법:** `{브랜치 단위}/{이슈번호}`
    * **`브랜치 단위`는 해당 이슈에 붙은 label과 동일하게 맞춥니다.** (label `refactor` → `refactor/{이슈번호}`)
    * 항상 `feature/`가 아님에 유의합니다 — 이슈 label에 따라 접두어가 달라집니다.
* 예: `feature/1` (label: feature)
* 예: `fix/2` (label: fix)
* 예: `refactor/250` (label: refactor)
* 예: `docs/3` (label: docs)

### 📝 커밋(Commit) 메시지 규칙

* **형식:** `[라벨] #{이슈번호} {내용}`
* 예: `[Feat] #23 로그인 기능 구현`

### 🚀 PR (Pull Request) 규칙

* **PR 규모/단위:** 변경된 파일 개수가 10~15개 정도로 되도록 작게 쪼개어 PR을 올립니다.
    * 파일 개수 범위는 대략적인 가이드라인이며, 상황에 따라 유연하게 적용할 수 있습니다.
* **PR 제목 형식:** `[라벨] #{이슈번호} {내용}`
    * 예: `[Feat] #1 로그인 기능 구현`
    * 예: `[Chore] #2, 3 이슈 및 PR 템플릿 생성` (다중 이슈는 쉼표로 구분)

* **develop 머지 규칙:**
    * `{label}/1` 등의 브랜치를 `develop`으로 머지합니다.
    * 별도의 **Approve(코드 리뷰)는 머지 필수 조건이 아닙니다.** 리뷰는 선택(권장)이며, 받지 않고도 머지할 수 있습니다.

* **main 머지 규칙:**
    * 우선적으로 `develop`에만 Push하며, `main`은 완벽히 준비되었을 때만 머지합니다.
    * 별도의 **Approve(코드 리뷰)는 머지 필수 조건이 아닙니다.**

### 🔒 검증 파이프라인 (로컬 훅 + CI)

커밋·PR이 팀 형식과 코드 스타일을 자동으로 지키도록 **로컬 git 훅**과 **CI 게이트** 2중으로 검증합니다.
(AI 작업 사이클에서 이 파이프라인이 어떤 회복 루프로 도는지는 [`ai-workflow-guide.md`](ai-workflow-guide.md) 10장 참고.)

#### ① 로컬 git 훅 활성화 (신규 합류자 1회 설정)

훅은 `.githooks/`에 들어 있으며, **각자 로컬에서 한 번** 아래를 실행해야 동작합니다(빠뜨리면 커밋 시 검증이 돌지 않습니다).

```bash
git config core.hooksPath .githooks
```

> 저장소 클론 직후 한 번만 설정하면 됩니다. 설정 여부는 `git config core.hooksPath`로 확인할 수 있습니다(값이 `.githooks`면 활성).

#### ② pre-commit — 자동교정 루프

커밋 시 스테이징된 `.java` 파일이 있으면 다음 순서로 돕니다.

1. **`./gradlew spotlessApply`** — 포맷을 **자동 수정**합니다.
2. 자동 수정으로 바뀐 파일을 **다시 스테이징**합니다(원래 스테이징돼 있던 `.java`만).
3. **`./gradlew checkstyleMain checkstyleTest`** — 통과해야 커밋이 진행됩니다.

> 포맷 검증은 **자동으로 보장**됩니다 — pre-commit이 `spotlessApply`로 자동 교정하고, CI(`ci.yml`)가 `spotlessCheck`로 최종 검사합니다. 따라서 사람이 작업 완료 전 별도로 `spotlessCheck`를 돌릴 필요는 없습니다(원하면 로컬에서 `./gradlew spotlessCheck`로 미리 확인할 수는 있음).

#### ③ commit-msg — 형식 검증

커밋 메시지 첫 줄이 `[Type] #이슈번호 요약` 형식이어야 통과합니다(Merge/Revert/fixup/squash 커밋은 예외). 형식은 위 **커밋 메시지 규칙**(§1)과 동일합니다.

#### ④ CI 게이트 (`.github/workflows/ci.yml`)

`develop`을 대상으로 하는 PR(`opened`/`synchronize`/`reopened`)에서 자동 실행됩니다.

* **단계:** `spotlessCheck` → `checkstyle` → `test` → `build` (job 이름 `build`).
* **PR 제목 검증:** 별도 워크플로우 `pr-title.yml`(job `validate-title`)이 `[Type] #이슈번호 내용` 형식을 검사합니다.
* **concurrency:** 같은 PR에 연속 push하면 진행 중이던 이전 run을 자동 취소해 러너를 절약합니다.
* 머지 전 **CI 그린**이 조건입니다(Approve는 필수 아님 — 위 머지 규칙 참고).

> `develop` 브랜치에는 **branch protection이 이미 적용**되어 `build`(`ci.yml`)·`validate-title`(`pr-title.yml`)가 필수 상태 체크로 요구됩니다(Approve는 강제하지 않음). 적용 변경은 admin 권한이 필요합니다.

## 2. 코드 및 네이밍 규칙

### 📁 디렉토리 구조 및 파일명

* **디렉토리 구조:** 프로젝트 내의 `docs/ddd.md` 문서를 참고합니다.
* **Docs 파일명 규칙:** **kebab-case** 적용 (단어 사이를 하이픈 `-`으로 연결)
* 예: `kafka-event-guide.md`

### 🔡 파일명 컨벤션

| 항목                     | 규칙                          | 예시                                                               |
|------------------------|-----------------------------|------------------------------------------------------------------|
| **Class 이름 (UseCase)** | `{module}{function}UseCase` | `MarketCompleteOrderPaymentUseCase`                              |
| **Controller**         | `{module}Controller`        | `MemberController`                                               |
| **Timestamp 필드**       | `{동사}edAt`                  | `createdAt`, `updatedAt`, `deletedAt`                            |
| **Table**              | 소문자 또는 `소문자_소문자`            | `@Table(name = "booking")`<br>`@Table(name = "payment_account")` |

### 🔠 자바 네이밍 컨벤션

| 항목            | 컨벤션        | 예시                                                                                                                       |
|---------------|------------|--------------------------------------------------------------------------------------------------------------------------|
| **File**      | PascalCase | `MemberController.java`                                                                                                  |
| **Package**   | lowercase  | `boundedcontext`                                                                                                         |
| **Class**     | PascalCase | `UserProfile`                                                                                                            |
| **Method**    | camelCase  | 단건 조회: `getMember()`<br>목록 조회: `getMembers()`<br> 생성: `createMember()`<br> 수정: `updateMember()`<br> 삭제: `deleteMember()` |
| **Variable**  | camelCase  | `memberName`, `members`                                                                                                  |
| **Parameter** | camelCase  | `marketMemberDto`                                                                                                        |

> 💡 참고자료:
> [Naver Hackday Java Convention](https://naver.github.io/hackday-conventions-java/) / [Google Java Style Guide](https://github.com/google/styleguide/blob/gh-pages/intellij-java-google-style.xml)

### ⚙️ 서비스 간 호출 프로퍼티 키 (RestClient)

다른 서비스를 동기 호출(`RestClient`)할 때 `application.yml` 의 프로퍼티 키와 값을 아래 규칙으로 통일합니다.

* **서비스 URL 키:** `service.<name>.url`
    * `<name>` 은 호출 대상 서비스의 **단축명**(`-service` 접미사 제외): `auth`, `user`, `seat` 등
    * 값은 **환경변수 오버라이드** 형태: `${<NAME>_SERVICE_URL:http://localhost:<port>}`
    * 예: `service.auth.url: ${AUTH_SERVICE_URL:http://localhost:8082}`
* **공통 타임아웃 키:** 모듈당 호출 대상이 1개이므로 모듈 공통 1쌍으로 둡니다.
    * `service.http.connect-timeout-ms: ${SERVICE_HTTP_CONNECT_TIMEOUT_MS:3000}`
    * `service.http.read-timeout-ms: ${SERVICE_HTTP_READ_TIMEOUT_MS:10000}`
* **타임아웃 적용:** `RestClient` 빈은 공통 모듈의 `RestClientFactorySupport.withTimeouts(connectMs, readMs)`(`common/.../global/config`)로 생성한 `ClientHttpRequestFactory` 를 반드시 적용합니다. 타임아웃 미설정 시 상대 서비스 지연이 Kafka 컨슈머 스레드 등을 장시간 블로킹할 수 있습니다.

```yaml
service:
  auth:
    url: ${AUTH_SERVICE_URL:http://localhost:8082}
  http:
    connect-timeout-ms: ${SERVICE_HTTP_CONNECT_TIMEOUT_MS:3000}
    read-timeout-ms: ${SERVICE_HTTP_READ_TIMEOUT_MS:10000}
```

> **별개 패턴(통일 대상 아님):** 외부 PG 호출은 `payment.pg.toss.*`(외부 결제 네임스페이스), 게이트웨이 라우팅은 `services.<name>.host`(호스트 전용, 자바 주입 없음)로 의미가 달라 위 규칙과 구분합니다.

## 3. API 및 응답/예외 처리 규칙

### 🌐 API 엔드포인트 규칙

* **Prefix 형식:** `/api/{version}/{module_name}/`
    * 예: `GET` `/api/v1/member/`
    * **주의:** 모듈 이름은 항상 **단수형**으로 사용합니다.


* **RESTful API 규칙:**
    * 리스트 조회: `/{module_name}`
    * 단건 조회: `/{module_name}/{id}`
    * 예: 판매자 등록 👉 `/api/v1/seller` (POST)
    * `PUT` / `PATCH` 👉 상황에 맞게 구분하여 사용
    * `DELETE` 👉 기본적으로 **Soft Delete**를 기준으로 구현

### 📜 Swagger 설정 규칙

API 문서화는 **`@Tag`(클래스) + `@Operation`(메서드)** 만 사용합니다. 모든 도메인(user / auth / booking / ticket / performance / payment 등)이 동일한 방식을 따릅니다.

* **Class 레벨:** `@Tag` 어노테이션으로 도메인 단위 그룹을 지정합니다.

```java
@Tag(name = "Member", description = "회원 도메인 API")
@RestController
@RequestMapping("/api/v1/member")
public class MemberController { ...
}
```

* **Method 레벨:** `@Operation(summary, description)` 으로 각 API의 요약과 설명을 작성합니다.

```java
@Operation(summary = "소셜 로그인", description = "인가 코드를 통해 사용자 인증 후 JWT 토큰(access, refresh)을 발급합니다.")
@PostMapping("/social/login")
public ResponseEntity<ApiResponse<OauthLoginResponse>> socialLogin(
    @RequestBody @Valid SocialOauthLoginRequest request) { ... }
```

* **실패 응답:** 컨트롤러에 `@ApiResponse` / 커스텀 `*ApiResponses` 분리 파일을 **부착하지 않습니다.** 에러는 전역 `ApiResponse` 구조와 `ErrorStatus`(전역 예외 처리)로 일관되게 반환되므로, 엔드포인트별 실패 응답을 Swagger 어노테이션으로 중복 기술하지 않습니다. (전역 응답 구조는 아래 **🔄 전역 응답 통일 (ApiResponse)** 섹션 참고)

* **파라미터 설명(선택):** 경로/쿼리 파라미터에 부연 설명이 필요한 경우에만 `@Parameter` 를 사용합니다.

### 🔄 전역 응답 통일 (ApiResponse)

API 응답은 `ApiResponse` 클래스를 통해 일관된 구조로 반환합니다.

**✅ 성공 응답 사용법**

```java
// 1. 반환 데이터(DTO)가 없는 경우
return ApiResponse.onSuccess(SuccessStatus.OK);

// 2. 반환 데이터(DTO)가 있는 경우
ResponseDto response = memberService.getMember();
return ApiResponse.

onSuccess(SuccessStatus.OK, response);

```

**❌ 실패 응답 (ErrorStatus) 규칙**

* **상태 코드 형식:** `모듈_상태코드_세자리번호` (예: `ORDER_404_001`)
* **4000번대 에러 코드 사용 이유:** 400번대 HTTP 상태 코드를 나타내기 위함. 400~499는 너무 제한적이므로 프로젝트 확장을 고려하여 4000번대로 설계합니다.
* ControllerAdvice를 사용하고 있으므로, Service 단에서 Exception을 던지면 자동으로 실패 응답이 반환됩니다.

```java
// Service 내 에러 발생 예시
Diary diary = diaryRepository.findByDiaryIdAndUserId(diaryId, userId)
    .orElseThrow(() -> new BusinessException(ErrorStatus.DIARY_NOT_FOUND));

```

### 🧩 공통 모듈 주요 클래스 (빌딩블록)

`common` 모듈이 제공하는, 전 서비스가 재사용하는 핵심 클래스입니다. (응답/에러 3종은 위 §3 참고)

| 클래스 | 설명 |
|--------|------|
| `ApiResponse` | 응답 래퍼 (`onSuccess(status)`, `onSuccess(status, result)`, `onSuccess(status, Page<T>)`) — 상세 §3 |
| `SuccessStatus` | 성공 코드 enum — 상세 §3 |
| `ErrorStatus` | 에러 코드 enum (`모듈_상태코드_세자리번호`) — 상세 §3 |
| `BusinessException` | 비즈니스 예외 — `GlobalExceptionHandler`가 자동으로 실패 응답으로 변환 |
| `PageInfo` | 오프셋 페이징 정보 record (`pageIndex, size, hasNext, totalElements, totalPages`) |
| `CursorInfo` | 커서 페이징 정보 record (`hasNext, nextCursor, size`) |
| `AutoIdBaseEntity` | auto increment PK 기반 엔티티 상위 클래스 |
| `BaseTimeEntity` | `createdAt`, `updatedAt` 포함 상위 클래스 |

## 4. 환경 및 기타 규칙

### 🛠 버전 정보

* **Java:** 21
* **Spring Boot:** 4.0.3 (LTS)

### 🏷 주석 컨벤션

* **코드 길이 제한:** 100자 (Checkstyle `LineLength max=100` 강제)
* **1줄 주석:** `// 주석 내용`
* **여러 줄 주석:**
    * 코드 블록 설명: `/* 주석 내용 */`
    * 클래스/메서드/필드 설명 (API 문서화용): `/** 주석 내용 */`

### 🔁 @Transactional 규칙

* Facade가 아닌, 하나의 작업 단위인 **UseCase** 계층에 작성합니다.
* 클래스와 메서드 레벨 모두 적용 가능하지만, **메서드 레벨의 설정이 우선(Overriding)** 적용됩니다.

### 🧪 Controller 테스트 어노테이션

* Controller 슬라이스 테스트 작성 시 `@WebMvcTest` 대신 `@WebMvcSliceTest`를 사용합니다.
* **이유:** 공통 `JacksonConfig`를 포함하여, 테스트 환경에서도 실제 API 응답과 동일하게 JSON 필드명(snake_case 등)이 직렬화되는지 정확히
  검증하기 위함입니다.

```java

@WebMvcSliceTest(SeatController.class)
class SeatControllerTest {
  // ...
    .

  andExpect(jsonPath("$.is_success").

  value(true))
    .

  andExpect(jsonPath("$.result.available_count").

  value(8))
}
```

### 📨 Kafka 컨슈머 에러 처리 · 상수화

* **컨슈머 에러 처리 정책(일시/영구 예외 구분, ack vs re-throw)과 groupId/topic 상수화 컨벤션의 SSOT는 [`kafka-event-guide.md`](kafka-event-guide.md) §2**입니다. `@KafkaListener` 리스너를 추가·수정할 때 그 표준(영구→ack, 일시→re-throw→DLT, `KafkaConsumerErrorPolicy`·`KafkaConsumerGroup` 상수)을 따릅니다. 여기에 재기술하지 않습니다.

### 🔐 이벤트 payload · DLT 보안 규칙

* **이벤트 payload에 PII(이메일·카드번호·개인정보) 금지** — 이벤트에는 식별자(ID)·금액·시간 등 최소 정보만 담습니다. **payload에 PII를 넣지 않는 것이 1차 통제**이며 마스킹은 보조 수단입니다.
* **DLT 저장 전 마스킹** — 재시도 상한을 초과해 `dead_letter_record`에 적재되는 실패 메시지는 저장 직전 `DltPayloadMasker`로
  PII(이메일·카드·전화·주민번호)를 마스킹합니다. **보수적 best-effort 패턴만** 커버하며 전수 보장이 아닙니다(정상 ID 오탐 방지 우선).
* **DLT 자동 보존/삭제** — `dead_letter_record`는 기본 **30일** 보존 후 retention 배치가 자동 삭제합니다.
  보존 기간은 `app.dlt.monitor.retention-days`(환경변수 `DLT_RETENTION_DAYS`)로 조정합니다.
* **Kafka .DLT 토픽 잔존 주의** — DB 저장값은 마스킹되지만 원본 메시지는 Kafka `.DLT` 토픽에 해당 토픽의 retention 기간 동안 잔존합니다. 운영 환경에서는 Kafka 토픽 retention 설정도 함께 관리하세요.