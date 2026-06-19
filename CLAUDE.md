# TicketRush 팀 컨벤션

## 기술 스택

- Java 21
- Spring Boot 4.0.3 (LTS)

---

## Git 컨벤션

### 커밋 메시지
```
[라벨] #이슈번호 {내용}
예: [Feat] #23 로그인 기능 구현
```

### 브랜치 규칙
- 형식: `라벨/이슈번호`
  - 예: `feature/1`, `fix/2`
- 브랜치 종류: `main`, `develop`, `feature`, `fix`(bug 아닌 fix로 통일), `refactor`, `infra`, `chore`, `docs`, `test`
- `feature/*` → `develop` 으로 머지 (main은 완성 시에만)

### PR 규칙
- 제목: `[라벨] #이슈번호 {내용}`
  - 다중 이슈: `[Chore] #2, 3 이슈 및 PR 템플릿 생성`
- UseCase 단위로 작게 PR
- 리뷰어 지목 필수, comment 무조건 달기, approve 1명 이상이면 merge

### 이슈 규칙
- 제목: `[모듈] {내용}`
  - 예: `[회원] 로그인 기능 구현`
- 다른 사람 담당 부분의 수정사항 발견 시 → 그 사람이 이슈 생성

---

## 프로젝트 아키텍처

**MSA (Micro Service Architecture)** - Gradle 멀티 모듈

### 모듈 목록
| 모듈 | 역할 |
|------|------|
| `common` | 전 모듈 공통 코드 (ApiResponse, ErrorStatus, PageInfo, Kafka, Redis 등) |
| `gateway-service` | API Gateway |
| `auth-service` | 인증/인가 (OAuth2, JWT) |
| `user-service` | 회원 도메인 |
| `performance-service` | 공연 도메인 (이슈 #19 현재 작업 중) |
| `seat-service` | 좌석 도메인 |
| `booking-service` | 예매 도메인 |
| `order-service` | 주문 도메인 |
| `payment-service` | 결제 도메인 |
| `ticket-service` | 티켓 도메인 |

### 서비스 내부 DDD 계층 구조
```
boundedcontext/{도메인}/
├── app/
│   ├── facade/          # UseCase 조합 진입점 (트랜잭션 없음)
│   ├── usecase/         # 비즈니스 로직 + @Transactional
│   ├── dto/
│   │   ├── request/
│   │   └── response/
│   ├── mapper/          # DTO ↔ Entity 변환
│   └── support/         # 공통 유틸 (enum 변환, 파싱 등)
├── domain/
│   ├── entity/          # JPA Entity
│   ├── types/           # Enum
│   └── policy/          # 도메인 정책
├── in/
│   ├── api/v1/          # REST Controller (버전별 분리)
│   │   └── swagger/     # Swagger 어노테이션 분리
│   ├── eventlistener/   # Kafka Consumer
│   └── scheduler/       # 스케줄러
└── out/
    ├── repository/      # JPA Repository
    └── apiclient/       # 외부 API 연동

global/                  # 서비스별 전역 설정 (SecurityConfig 등)
```

### 공통 모듈 주요 클래스
- `ApiResponse` — 응답 래퍼 (`onSuccess(status)`, `onSuccess(status, result)`, `onSuccess(status, Page<T>)`)
- `PageInfo` — 오프셋 페이징 정보 record (`pageIndex, size, hasNext, totalElements, totalPages`)
- `CursorInfo` — 커서 페이징 정보 record (`hasNext, nextCursor, size`)
- `ErrorStatus` — 에러 코드 enum (형식: `모듈_상태코드_세자리번호`)
- `SuccessStatus` — 성공 코드 enum
- `BusinessException` — 비즈니스 예외 (GlobalExceptionHandler가 처리)
- `AutoIdBaseEntity` — auto increment PK 기반 entity 상위 클래스
- `BaseTimeEntity` — createdAt, updatedAt 포함

### 워크플로우
- 이슈/PR 조회: `gh issue view {번호}` 또는 `gh pr view {번호}` 로 직접 가져옴
- 브랜치: `feature/{이슈번호}` 기준으로 작업

## 디렉토리 구조 상세

`docs/ddd.md` 참고

---

## API 규칙

- prefix 형식: `/api/{version}/{module_name}/`
  - 예: `GET /api/v1/member/`
  - 모듈 이름은 항상 **단수**
- 리스트 조회: `/`
- 단건 조회: `/{id}`
- PUT/PATCH: 기호에 맞게 선택
- DELETE: Soft Delete 기준

---

## 코드 컨벤션

### 네이밍

| 항목 | 컨벤션 | 예시 |
|------|--------|------|
| File | PascalCase | `MemberController.java` |
| Package | lowercase | `boundedcontext` |
| Class | PascalCase | `UserProfile` |
| Method | camelCase | `createMember()`, `updateMember()`, `deleteMember()`, `getMember()`, `getMembers()` |
| 변수 | camelCase | `memberName`, `members` |
| 매개변수 | camelCase | `marketMemberDto` |

### 파일명 컨벤션

| 항목 | 규칙 | 예시 |
|------|------|------|
| UseCase 클래스 | `{Module}{Function}UseCase` | `MarketCompleteOrderPaymentUseCase` |
| Controller | `{Module}Controller` | `MemberController` |
| Timestamp 필드 | `{동사}edAt` | `createdAt`, `updatedAt`, `deletedAt` |
| DB 테이블명 | 소문자 또는 소문자_소문자 | `booking`, `payment_account` |
| Docs 파일 | kebab-case | `kafka-event-guide.md` |

### 코드 스타일

- 코드 줄 길이 제한: **120자**
- Java 컨벤션: [Naver Java 컨벤션](https://naver.github.io/hackday-conventions-java/) + [Google Java Style](https://github.com/google/styleguide/blob/gh-pages/intellij-java-google-style.xml)

---

## 주석 규칙

```java
// 1줄 주석

/* 2줄 이상 - 코드 설명 */

/**
 * 2줄 이상 - 클래스/메서드/필드 설명 (Javadoc)
 */
```

---

## 응답 통일 (ApiResponse)

### 사용법

```java
// DTO 없는 성공 응답
return ApiResponse.onSuccess(SuccessStatus._OK);

// DTO 있는 성공 응답
return ApiResponse.onSuccess(SuccessStatus._OK, response);

// 실패 응답 → 서비스에서 예외만 던지면 ControllerAdvice가 처리
throw new BusinessException(ErrorStatus.DIARY_NOT_FOUND);
```

### ErrorStatus / SuccessStatus 형식

형식: `모듈_상태코드_세자리번호` → `ORDER_404_001`

```java
// ErrorStatus 예시
PERFORMANCE_NOT_FOUND(HttpStatus.NOT_FOUND, "PERFORMANCE_404_001", "공연이 존재하지 않습니다."),
PERFORMANCE_MAIN_IMAGE_MISSING(HttpStatus.BAD_REQUEST, "PERFORMANCE_400_001", "메인 이미지는 필수입니다."),

// SuccessStatus 예시
_OK(HttpStatus.OK, "COMMON200", "성공입니다."),
_CREATED(HttpStatus.CREATED, "COMMON201", "리소스가 성공적으로 생성되었습니다."),
```

---

## Swagger 컨벤션

```java
// 클래스 레벨
@Tag(name = "Performance Admin", description = "공연 관리자 API")

// 메서드 레벨
@Operation(summary = "공연 등록", description = "새로운 공연 정보를 등록합니다.")

// 실패 응답 → 별도 어노테이션 파일로 분리 후 메서드에 적용
@DiaryNotFoundApiResponse
```

실패 응답 어노테이션 파일 구조:

```java
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ApiResponses({
    @ApiResponse(
        responseCode = "404",
        description = "...",
        content = @Content(
            mediaType = "application/json",
            schema = @Schema(implementation = ApiResponse.class),
            examples = @ExampleObject(name = "...", value = SwaggerExamples.SOME_ERROR)
        )
    )
})
public @interface SomeErrorApiResponse {}
```

---

## @Transactional 규칙

- **Facade가 아닌 UseCase**에 작성
- 클래스 레벨 또는 메서드 레벨 선택 적용 (메서드 레벨이 우선)

```java
// UseCase 예시
@Service
@RequiredArgsConstructor
public class PerformanceCreateUseCase {

    @Transactional
    public PerformanceCreateResponse execute(...) { ... }
}
```