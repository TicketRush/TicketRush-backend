# 2. 외부용/내부용 API를 URL로 구분한다

날짜: 2026-07-09

## 상태

승인됨

## 맥락

MSA에서는 외부 클라이언트가 호출하는 API와 서비스 간 내부 호출용 API를 구분해야, 외부 클라이언트가 내부 전용 엔드포인트에 접근하지 못하도록 보안 처리를 하기 쉽다.

현재 TicketRush는 이 구분 규칙이 서비스마다 제각각이라 방식이 세 갈래로 갈려 있다.

- **경로 분리(정석):** user-service만 `/api/v1/internal/user/...` 형태로 `internal`을 버전 직후에 둔다. Gateway 라우트에 `/api/v1/internal/**`이 없어 외부에서 도달 자체가 불가능하다.
- **모듈명 뒤 internal(외부 노출):** booking(`/api/v1/booking/internal/{id}`)·seat(`/api/v1/seat/internal/sold`)는 `internal`이 모듈명 뒤에 있어, Gateway의 서비스별 와일드카드 라우트(`/api/v1/booking/**`, `/api/v1/seat/**`)에 걸려 **외부로 노출**된다. 방어가 컨트롤러 인라인 토큰검증에만 의존한다.
- **표식 없음(무방비 노출):** performance(`/api/v1/performance/{id}/validate`)는 클래스명만 `Internal`일 뿐 경로에 표식도, 토큰 검증도 없이 외부에 열려 있다.
- **하드코딩 차단 필터:** auth의 `email-verification/verified`·`consume`은 외부용과 같은 `/api/v1/auth/...` 경로를 쓰면서, Gateway의 `InternalAuthEndpointBlockFilter`가 특정 method+path 문자열을 하드코딩 비교해 403으로 막는다. 내부 엔드포인트를 추가·변경할 때마다 이 필터도 함께 고쳐야 하고, 깜빡하면 조용히 외부에 노출되는 취약 구조다.

구조적으로, 이 프로젝트의 내부 서비스 호출은 Feign이 아니라 `RestClient`로 대상 서비스 포트에 **직결(Gateway 우회)**된다. 따라서 Gateway에서 특정 URL prefix를 외부에 대해 차단해도 내부 호출에는 영향이 없다 — 즉 URL prefix 구분만으로 외부 차단이 성립하는 조건이 이미 갖춰져 있다.

## 결정

외부용 API와 내부(서비스 간)용 API를 **URL로 구분**한다.

- 외부용: `/api/{version}/{module_name}/...`
- 내부용: `/api/{version}/internal/{module_name}/...`

`internal` 세그먼트는 반드시 **버전 접두사 직후·모듈명 앞**에 둔다(`/api/v1/internal/booking` ✅, `/api/v1/booking/internal` ❌). Gateway에는 `/api/v1/internal/**`을 **라우트로 등록하지 않아** 외부 요청이 도달하지 못하게 한다. 내부용 엔드포인트에는 `hasRole("INTERNAL")` + `X-Internal-Token` 검증(공통 `InternalApiTokenFilter`)을 적용하고, 경로별 하드코딩 차단 필터에는 의존하지 않는다.

이 규칙은 컨벤션 SSOT인 [`docs/backend-convention.md`](../backend-convention.md) §3 "API 엔드포인트 규칙"에 반영한다.

## 결과

- 내부 전용 엔드포인트가 Gateway 라우트 미등록만으로 외부에서 차단되어, 개별 경로 하드코딩 차단·컨트롤러 인라인 검증·라우트 누락에 의한 암묵적 숨김이 뒤섞인 상태가 하나의 규칙으로 정리된다.
- 새 내부 엔드포인트를 추가할 때 Gateway 필터를 함께 고쳐야 하는 동기화 부담이 사라진다(`internal` prefix만 지키면 됨).
- 기존 코드 중 규칙에 어긋나는 곳을 옮기는 후속 리팩터가 필요하다: seat/booking(#366), performance(#367), auth + `InternalAuthEndpointBlockFilter` 제거(#368). 경로 이동 시 호출자 `RestClient`의 uri도 함께 갱신해야 한다.
- 외부/내부 경로가 분리되므로 향후 Gateway에서 내부 API에 대한 추가 정책(네트워크 격리, mTLS 등)을 prefix 단위로 일괄 적용하기 쉬워진다.
