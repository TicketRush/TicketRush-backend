# 21. 공연 캐릭터 구성은 해석하지 않는 JSON 컬럼에 문자열로 저장하고, 스키마는 프론트가 소유한다

날짜: 2026-09-15

## 상태

승인됨

## 맥락

공연 상세의 3D 캐릭터는 처음에 완성 GLB 파일을 `model3d` 파트로 업로드해 `image3d_url`에 저장하는 구조였다. 그런데 프론트의 캐릭터 커스터마이징은 완성 GLB 하나를 만드는 방식이 아니라, 기존 GLB 파츠(body/hair/outfit 등)를 조합하고 색상 값을 입혀 재구성하는 방식이다. 공연마다 같은 파츠가 든 GLB를 새로 올리면 파일 중복만 늘어난다. 그래서 [#650](https://github.com/TicketRush/TicketRush-backend/issues/650)에서 **완성 GLB 대신 구성 정보(`characterConfig`)와 한마디(`characterMessage`)만 저장하고, 조회 시 프론트가 기존 파츠로 재구성**하기로 했다. `model3d` 파트는 삭제하지 않고 선택으로 바꾼다.

이 레포에서 JSON 컬럼은 이것이 처음이라 두 가지를 정해야 했다.

**첫째, 백엔드가 JSON의 내용을 얼마나 알아야 하는가.** 프론트가 제시한 구조는 `schemaVersion`, `baseModelId`, `skinColor`, `outfitModelId`, 의상별 색상, `animationId` 등 13개 안팎의 필드이고, 리깅·애니메이션이 붙으면서 필드가 계속 늘어날 예정이다. 백엔드가 이 필드들을 컬럼이나 타입으로 알면 필드가 늘 때마다 백엔드 변경과 배포가 따라붙는다.

**둘째, Hibernate가 JSON을 어떤 Java 타입으로 다루게 할 것인가.** 조사에서 다음이 확인됐다.

- 이 레포의 Hibernate는 7.2.4(Spring Boot 4.0.3 BOM)이고, JSON 컬럼용 `FormatMapper`는 **Jackson 2(`com.fasterxml`)만 인식한다**(`JacksonIntegration`·`JacksonJsonFormatMapper`의 바이트코드가 `com/fasterxml/jackson/databind/ObjectMapper`만 참조. Jackson 3용 `FormatMapper`는 Hibernate 7.3부터 — [Hibernate ORM 7.3 What's New](https://docs.hibernate.org/orm/7.3/whats-new/)).
- 앱의 HTTP 직렬화는 Jackson 3(`tools.jackson` 3.0.4)이다. Jackson 2 databind 2.20.2는 우리가 선언한 의존이 아니라 shedlock·actuator·data-jpa·awspring의 **전이 의존으로만** classpath에 있다.
- 다만 Jackson 2를 테스트 classpath에서 빼 보면 Hibernate에 닿기도 전에 Spring Framework 7의 `DefaultHttpMessageConverters`가 `com.fasterxml.jackson.databind.exc.InvalidDefinitionException`을 찾지 못해 `RestClient` 빈 생성에서 기동이 깨진다(실측). 즉 이 앱에서 Jackson 2는 당장 사라질 수 없지만, 그것은 우리가 통제하는 조건이 아니다.
- `AbstractJsonFormatMapper`는 Java 타입이 `String`이면 `FormatMapper` 변환을 거치지 않고 문자열을 그대로 넘긴다.

## 결정

### 1. 백엔드는 `characterConfig`의 내용을 해석하지 않는다. 스키마는 프론트가 `schemaVersion`으로 소유한다

백엔드가 검증하는 것은 두 가지뿐이다 — **JSON 객체인가**(문자열·배열·숫자는 400), **compact 직렬화 UTF-8 기준 4,096바이트 이하인가**(프론트 예시 약 400바이트의 10배), **중첩 깊이 32 이하인가**(MySQL 8 JSON 컬럼이 깊이 100 초과를 저장 단계에서 거절하므로 요청에서 먼저 막는다). 이 검증은 요청 DTO의 커스텀 제약(`@JsonObjectMaxBytes`)이 하고, 위반은 다른 필드 검증과 같은 `VALID_400_001`로 나간다. 키·값·필수 필드는 보지 않는다. `animationId` 같은 필드가 추가되어도 백엔드는 바뀌지 않는다.

`characterMessage`는 캐릭터 외형이 아니라 공연에 종속된 문구라 JSON 밖의 별도 `varchar(50)` 컬럼에 둔다. 프론트 UI 제한이 50자다.

### 2. 엔티티 필드는 `String` + `@JdbcTypeCode(SqlTypes.JSON)`으로 매핑한다

`Map<String, Object>`나 `JsonNode`로 매핑하면 Hibernate가 Jackson 2 `ObjectMapper`로 직렬화한다 — 앱이 Jackson 3인데 저장 경로만 전이 의존 Jackson 2에 기대는 조합이 된다. Jackson 3 `JsonNode`를 Jackson 2가 다루게 하는 조합은 검증된 바 없다. `String`은 그 경계를 아예 타지 않는다.

대신 변환은 앱이 한다. 요청 DTO는 Jackson 3 `JsonNode`로 받아(어떤 JSON이 와도 역직렬화가 실패하지 않아 검증기까지 도달하고 `isObject()` 판정이 한 줄이다) 매퍼가 `JsonNode.toString()`(compact)으로 문자열을 만들어 저장하고, 상세 응답은 매퍼가 문자열을 다시 트리로 읽어 싣는다. 이 파싱에는 전역 `JacksonConfig`(SNAKE_CASE·NON_NULL)를 타지 않는 별도 `JsonMapper`를 쓴다. 전역 SNAKE_CASE는 POJO 프로퍼티명에만 적용되고 트리의 키는 건드리지 않으며, 이는 테스트로 고정했다.

컬럼 타입은 H2(MySQL 모드)·MySQL 8 모두 `json`으로 생성되고, MySQL 8 `json` 컬럼에 대해 `ddl-auto: validate`가 통과함을 로컬 컨테이너로 확인했다.

### 3. "보낸 JSON이 그대로"는 값 동일성까지만 계약이다

MySQL 8은 JSON을 바이너리로 정규화해 **키를 알파벳순으로 재정렬하고 공백을 넣어** 돌려준다(실측: `{"schemaVersion":1,"outfitModelId":"festival"}` → `{"outfitModelId": "festival", "schemaVersion": 1}`). H2는 문자열을 그대로 돌려준다. 그래서 왕복 테스트는 문자열이 아니라 트리 비교로 쓰고, 프론트에는 키 순서가 보존되지 않는다고 알린다. 키 케이스·중첩·배열·값은 그대로다.

### 4. PATCH에서 `characterMessage`는 빈 문자열이 삭제다

이 레포의 PATCH 계약은 "null이면 수정하지 않는다"라 삭제를 표현할 값이 없었다. 프론트가 한마디를 지우는 동작이 필요해 **빈 문자열=삭제(null 저장)** 규칙을 이 필드에만 추가했다 — 레포 PATCH 최초의 예외다. 그래서 이 필드에는 `@NullOrNotBlank`를 붙이지 않는다. `Performance.update()`에 섞지 않고 `updateCharacter()`를 따로 두어 규칙이 한 곳에 고정되게 했다. 공백만 있는 문자열도 같은 기준으로 삭제한다 — 등록의 정규화(`blankToNull`)와 판정을 맞춰 같은 입력이 두 경로에서 다른 결과가 되지 않게 한다. `characterConfig`는 프론트가 캐릭터 제거 기능을 두지 않기로 해 삭제 규칙이 없다(덮어쓰기만).

### 5. `model3d` 파트는 선택이며 0바이트 파트는 보내지 않은 것으로 본다

컨트롤러의 `required`를 풀고 유스케이스는 파일이 있을 때만 검증·업로드한다. 0바이트 파트를 미전송으로 보는 정의는 파일 교체 API([#637](https://github.com/TicketRush/TicketRush-backend/issues/637))와 같다 — 파일을 고르지 않은 `<input type="file">`이 0바이트 파트를 보내기 때문이다. `mainImage`는 여전히 필수다. `PERFORMANCE_400_002`(3D 모델 파일 필수)는 결번 처리했다.

## 결과

- 캐릭터 필드가 늘어도 백엔드 변경 없이 흡수된다. 대신 백엔드는 JSON이 무엇을 뜻하는지 전혀 모르므로, 프론트가 스키마를 깨뜨리면 백엔드는 잡지 못한다. 그 책임은 `schemaVersion`과 함께 프론트에 있다.
- 저장 경로가 Jackson 2에 기대지 않는다. 다만 앱 전체는 여전히 전이 의존 Jackson 2 없이는 기동하지 못하며(위 실측), 이것은 이 결정과 무관한 상위 조건이다. `Map`·`JsonNode` 매핑으로 바꾸려는 사람은 Hibernate 7.3 이상에서 Jackson 3 `FormatMapper`가 잡히는지부터 확인해야 한다.
- 매퍼에 `JsonNode ↔ String` 변환 코드가 생겼다. 등록과 수정이 같은 `toJsonString`을 써 두 경로의 저장 문자열이 같다.
- prod는 `ddl-auto: validate`라 배포 전 수동 DDL이 필요하다(`Performance` javadoc과 PR 본문). 순서가 바뀌면 performance-service가 기동하지 못한다.
- 상세 응답이 최대 약 4KB 커진다. 목록 응답·Redis 목록 캐시에는 넣지 않았다.
- **프론트 전제 조건.** 프론트의 `axios-case-converter`가 `characterConfig` 안쪽 키까지 camel↔snake로 바꾼다(1.1.1 실측). 백엔드는 해석하지 않아 막을 수 없는데, 등록(멀티파트, #637에서 변환 우회)과 수정(JSON, 변환 적용)이 서로 다른 케이스로 저장되면 프론트가 자기 스키마의 키를 찾지 못해 **캐릭터 재구성이 실패한다**. 프론트가 `characterConfig`를 요청 변환에서 제외(또는 두 경로 모두 우회)했는지 확인하는 것이 이 기능의 배포 전제다. 키 순서 비보장과 함께 프론트에 전달한다.
