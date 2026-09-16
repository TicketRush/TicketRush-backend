# 22. OpenAPI 스키마의 프로퍼티 이름은 문서 생성기 쪽 리졸버에서 실제 계약에 맞춘다

날짜: 2026-09-17

## 상태

승인됨

## 맥락

`common`의 `JacksonConfig`가 전 모듈에 `PropertyNamingStrategies.SNAKE_CASE`를 걸고 있어 **실제 HTTP 요청·응답 본문은 전부 snake_case**다. 그런데 Swagger 문서는 Java 필드명 그대로 camelCase를 노출하고 있었다. 7개 서비스 전부, 공통 응답 래퍼까지 어긋났다 — 실제 `is_success`·`pagination_info`·`trace_id` ↔ 문서 `isSuccess`·`paginationInfo`·`traceId`.

[#650](https://github.com/TicketRush/TicketRush-backend/issues/650) 캐릭터 필드를 연동하던 프론트가 "Swagger는 `characterConfig`, 백엔드 테스트는 `character_config` — 어느 쪽이 계약이냐"고 물어오면서 드러났다. **문서를 믿고 구현하면 요청은 필드가 통째로 무시되고(검증도 통과한다) 응답은 `undefined`가 된다. 둘 다 에러 없이 조용히 어긋난다.**

원인은 문서 생성기와 런타임이 서로 다른 Jackson을 본다는 것이다.

- springdoc 3.0.1은 스키마 생성을 swagger-core 2.2.41에 위임하고, 그 매퍼는 **swagger가 들고 있는 static 싱글톤**(`Json31.mapper()`)이다. springdoc 소스에 `ModelResolver` 참조가 한 건도 없어 훅조차 없다.
- swagger-core는 Jackson 2(`com.fasterxml`), 앱은 Jackson 3(`tools.jackson`)다. 두 설정이 만날 지점이 없다.
- 설정 프로퍼티로는 해결되지 않고(`use-fqn`·`model-converters.*`에 naming 항목이 없다), 버전을 올려도 안 된다 — springdoc upstream이 아직 Jackson 2 계열이다.

문서에서 맞게 나오던 소수는 `@JsonProperty`로 이름을 직접 적은 것들이었다. Jackson 3도 애노테이션은 `com.fasterxml.jackson.annotation` 패키지를 쓰기 때문에 양쪽 매퍼가 같은 애노테이션을 읽는다.

## 결정

### 1. 매퍼에 네이밍 전략을 걸지 않고, `ModelResolver`를 상속해 만들어진 스키마의 이름만 바꾼다

가장 단순한 해법은 swagger-core의 매퍼에 같은 전략을 걸어 주는 것이다. 그런데 **두 방법 모두 다른 것을 깨뜨린다. 실측으로 확인했다.**

- **원본 매퍼에 걸면**: 그 인스턴스는 `ObjectMapperProvider`가 OpenAPI 문서 **자체를 직렬화**하는 데도 쓴다. 전략을 걸면 `requestBody`가 `request_body`가 되는 등 **문서 구조 키까지 snake로 깨진다.**
- **복제본(`copy()`·`ObjectMapperFactory.createJson31()`)에 걸면**: 문서 구조는 무사하지만 **sealed 타입의 하위 타입 해석이 실패한다.** `PaginationInfo`의 `oneOf`가 가리키는 `PageInfo`·`CursorInfo`가 통째로 사라져 **깨진 `$ref`만 남는다.**

그래서 매퍼는 기본 그대로 넘기고(= 하위 타입·파일·다형성 처리가 전부 원래대로 동작한다) `defineModel`로 들어오는 스키마의 프로퍼티 이름만 뒤에서 바꾼다. `required` 배열도 프로퍼티 이름을 담고 있어 함께 바꾼다 — 놓치면 문서상 없는 필드를 필수라고 말하게 된다.

이 방식은 **체인 순서도 안전하다.** 클래스명이 달라 기본 `ModelResolver`가 제거되지 않고, 우리 리졸버가 springdoc 자체 컨버터 9개 뒤·기본 리졸버 바로 앞에 놓인다(실측). 즉 `FileSupportConverter`·`PolymorphicModelConverter`·`OAS31ModelConverter`가 모두 먼저 동작한다.

### 2. 이름을 직접 적은 프로퍼티는 변환하지 않는다

두 경우를 제외한다.

- **`@JsonProperty`로 적은 이름** — 그 이름이 곧 실제 계약이다. `auth`의 `refreshToken`, `user`의 `socialId`·`socialProvider`가 여기 해당한다.
- **`@Schema(name = ...)`으로 적은 이름** — 문서에 그 이름으로 내보내겠다는 뜻이다.

**multipart 파트명이 두 번째 경로로 보호된다.** `@RequestPart("mainImage")`의 파트명은 JSON 프로퍼티가 아니라 서블릿 파트명이라 네이밍 전략의 대상이 아니다. 문서가 `main_image`로 나가면 그것을 믿은 클라이언트는 **메인 이미지만 조용히 교체에 실패한다** — [#637](https://github.com/TicketRush/TicketRush-backend/issues/637)에서 프론트의 case-converter가 파트명을 바꿔 실제로 났던 사고다. `model3d`·`gallery`·`request`는 소문자 한 덩어리라 변환 자체가 일어나지 않지만, `mainImage`는 두 SwaggerBody에서 `@Schema(name)`으로 고정했다.

`@RequestParam`·`@PathVariable`·`@ParameterObject`는 애초에 Jackson이 아니라 스프링이 바인딩하므로 영향이 없다(실측으로 확인).

### 3. `common`에 한 번만 두고, gateway만 사본을 갖는다

springdoc이 컨텍스트의 `ModelConverter` 빈을 모아 체인에 끼우므로 **common의 빈 하나가 7개 서비스 전부에 적용된다.** DTO를 건드릴 일은 없다.

`gateway-service`만 `common`을 의존하지 않아(모듈 8개 중 유일) 같은 클래스를 하나 더 둔다. `JacksonConfig`·`ApiResponse`가 이미 사본인 것과 같은 이유다. 게이트웨이는 각 서비스 문서를 `RewritePath`로 중계만 하므로, 사본이 관여하는 것은 대기열 API 스키마뿐이다. 대기열은 꺼져 있어 아직 피해가 없지만 [#472](https://github.com/TicketRush/TicketRush-backend/issues/472) 연동이 시작되면 같은 불일치가 그대로 사고가 된다.

## 결과

- 문서와 실제 계약이 일치한다. 프론트는 Swagger를 계약으로 읽을 수 있다.
- 신규 DTO에서 같은 불일치가 생기면 회귀 테스트가 잡는다. 서비스를 실제로 띄워 `/v3/api-docs`를 받아 camelCase 프로퍼티를 전수 검출하고, 계약상 camelCase인 이름만 화이트리스트로 허용한다. `ci.yml`이 `./gradlew test`를 돌리므로 별도 워크플로 없이 CI 게이트가 된다.
- **런타임 동작은 바뀌지 않는다.** 이 변경은 `/v3/api-docs` 산출물에만 작용하고 실제 직렬화 경로(`JacksonConfig`, Jackson 3)는 건드리지 않는다. 되돌리는 비용도 낮다 — 리졸버 빈 둘(`common`·gateway)과 `@Schema` 속성 둘이다. **되돌릴 때는 둘을 함께 걷어내야 한다.** `common`만 지우면 게이트웨이 문서만 snake로 남아 서비스 간 문서가 엇갈린다.
- springdoc이 Jackson 3를 지원하면 이 리졸버는 필요 없어진다. upstream에 전환 이슈가 열려 있다(springdoc #3169·#3200·#3268). 그때 매퍼에 전략을 거는 방식으로 단순화할 수 있는지 다시 볼 것.
- 문서 산출물을 실제로 검증하는 회귀 테스트는 performance와 gateway에 둔다. multipart 파트명이 있는 서비스와 `common`을 의존하지 않는 서비스가 각각 거기이기 때문이다. auth·user는 `@JsonProperty`로 이름을 직접 적은 DTO가 있어 그 보호만 컨버터 단위로 확인한다. 나머지 네 서비스(booking·payment·seat·ticket)는 같은 빈이 같은 규칙으로 동작하는 경로라 별도 테스트를 두지 않았다 — 규칙이 깨지면 performance 문서 테스트가 먼저 잡는다.
- 남은 불일치: 프론트가 지금까지 camelCase 문서를 보고 있었으므로, **문서가 바뀐다는 것과 예외 목록(multipart 파트명 3종·`refreshToken`·`socialId`·`socialProvider`)을 함께 공지해야 한다.**
