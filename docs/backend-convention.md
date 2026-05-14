# 📑 백엔드 개발 컨벤션 가이드

## 1. 협업 및 깃허브(GitHub) 컨벤션

### 👥 코드 리뷰

| 리뷰하는 사람 | 리뷰 받는 사람 |
|---------|----------|
| 혜림      | 민주       |
| 민주      | 소희       |
| 소희      | 혜림       |

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
* 예: `feature/1`
* 예: `fix/2`

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
    * `feature/1` 등의 브랜치를 `develop`으로 머지합니다.
    * 리뷰어를 반드시 지목하고, 리뷰어는 **무조건 코멘트를 작성**합니다.
    * **Approve 1명 이상**이면 Merge 가능합니다.

* **main 머지 규칙:**
    * 우선적으로 `develop`에만 Push하며, `main`은 완벽히 준비되었을 때만 머지합니다.
    * **Approve 1명 이상**이면 Merge 가능합니다.

## 2. 코드 및 네이밍 규칙

### 📁 디렉토리 구조 및 파일명

* **디렉토리 구조:** 프로젝트 내의 `docs/ddd.md` 문서를 참고합니다.
* **Docs 파일명 규칙:** **kebab-case** 적용 (단어 사이를 하이픈 `-`으로 연결)
* 예: `kafka-event-guide.md`

### 🔡 파일명 컨벤션

| 항목                     | 규칙                          | 예시                                    |
|------------------------|-----------------------------|---------------------------------------|
| **Class 이름 (UseCase)** | `{module}{function}UseCase` | `MarketCompleteOrderPaymentUseCase`   |
| **Controller**         | `{module}Controller`        | `MemberController`                    |
| **Timestamp 필드**       | `{동사}edAt`                  | `createdAt`, `updatedAt`, `deletedAt` |
| **Table**              | 소문자 또는 `소문자_소문자`            | `@Table(name = "booking")`<br>        

<br>`@Table(name = "payment_account")` |

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

* **Class 레벨:** `@Tag` 어노테이션 사용

```java

@Tag(name = "Member", description = "회원 도메인 API")
@RestController
@RequestMapping("/api/v1/member")
public class MemberController { ...
}

```

* **성공 응답 예시 (Method 레벨):** `@Operation` 어노테이션 사용

```java
  @Operation(
  summary = "일기 얼굴 사진 Presigned Download URL 발급",
  description = "사용자의 일기 얼굴 사진을 바로 볼 수 있는 presigned download url을 발급합니다.")

```

* **실패 응답 예시:** 공통 어노테이션을 생성하여 Controller 메서드에 부착

```java
  // 1. 공통 어노테이션 생성
@Target({ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ApiResponses({
  @ApiResponse(
    responseCode = "404",
    description = "존재하지 않는 일기를 요청했을 때 발생하는 에러입니다.",
    content = @Content(
      mediaType = "application/json",
      schema = @Schema(implementation = ApiResponse.class),
      examples = @ExampleObject(
        name = "DiaryNotFound",
        summary = "존재하지 않는 일기",
        value = SwaggerExamples.DIARY_NOT_FOUND_ERROR
      )
    )
  )
})
public @interface DiaryNotFoundApiResponse {

}

// 2. Controller에서 사용
@DiaryNotFoundApiResponse

```

### 🔄 전역 응답 통일 (ApiResponse)

API 응답은 `ApiResponse` 클래스를 통해 일관된 구조로 반환합니다.

**✅ 성공 응답 사용법**

```java
// 1. 반환 데이터(DTO)가 없는 경우
return ApiResponse.onSuccess(SuccessStatus._OK);

// 2. 반환 데이터(DTO)가 있는 경우
ResponseDto response = memberService.getMember();
return ApiResponse.

onSuccess(SuccessStatus._OK, response);

```

**❌ 실패 응답 (ErrorStatus) 규칙**

* **상태 코드 형식:** `모듈_상태코드_세자리번호` (예: `ORDER_404_001`)
* **4000번대 에러 코드 사용 이유:** 400번대 HTTP 상태 코드를 나타내기 위함. 400~499는 너무 제한적이므로 프로젝트 확장을 고려하여 4000번대로 설계합니다.
* ControllerAdvice를 사용하고 있으므로, Service 단에서 Exception을 던지면 자동으로 실패 응답이 반환됩니다.

```java
// Service 내 에러 발생 예시
Diary diary = diaryRepository.findByDiaryIdAndUserId(diaryId, userId)
    .orElseThrow(() -> new GeneralException(ErrorStatus.DIARY_NOT_FOUND));

```

## 4. 환경 및 기타 규칙

### 🛠 버전 정보

* **Java:** 21
* **Spring Boot:** 4.0.1 (LTS)

### 🏷 주석 컨벤션

* **코드 길이 제한:** 120자
* **1줄 주석:** `// 주석 내용`
* **여러 줄 주석:**
    * 코드 블록 설명: `/* 주석 내용 */`
    * 클래스/메서드/필드 설명 (API 문서화용): `/ 주석 내용 */`

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