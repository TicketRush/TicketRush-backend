# 18. 공연 파일은 퍼블릭 읽기 버킷에 올리고, DB에는 완성된 공개 URL을 저장한다

날짜: 2026-09-08

## 상태

승인됨

## 맥락

공연 등록의 파일 업로드가 구현되지 않은 채로 배포돼 있었다([#636](https://github.com/TicketRush/TicketRush-backend/issues/636)). `S3UploadUtils.uploadFile()`은 파일 내용을 어디에도 쓰지 않고 `https://ticketrush-fake-s3.s3.ap-northeast-2.amazonaws.com/temp/{UUID}.{ext}` 형태의 문자열만 만들어 반환했다. 등록 API는 201을 반환하고 응답에도 URL이 실렸으므로, **저장이 되지 않는다는 사실이 응답만으로는 드러나지 않았다.** 프론트가 캐릭터 제작소의 GLB를 `model3d` 파트로 연동하려고 스펙을 문의하면서 드러났다.

### 배선이 있다는 전제가 틀렸다

이슈 본문은 "버킷·자격증명 배선은 있고 업로드 코드만 비어 있다"고 적었다. 의존성(`spring-cloud-aws-starter-s3:4.0.0`)과 리전·자격증명은 실제로 있었다. 그러나 **버킷명을 담고 있던 `spring.cloud.aws.s3.bucket`은 awspring 4.0.0이 인식하는 프로퍼티가 아니다.** `spring-cloud-aws-autoconfigure` jar의 `spring-configuration-metadata.json`을 확인하면 `spring.cloud.aws.s3.*` 키 목록에 `bucket`이 없다(같은 이유로 `spring.cloud.aws.stack.auto`도 awspring 2.x 잔재다). 즉 `application-prod.yml`의 `${AWS_S3_BUCKET}`은 **어떤 빈도 바인딩하지 않는 죽은 설정**이었고, prod에 그 환경변수가 비어 있어도 아무 증상이 없었다.

### 결정해야 했던 것

완료조건이 "응답의 `image3dUrl`을 외부에서 **인증 없이 GET** 하면 업로드한 GLB 원본이 내려온다"였다. 이것이 공개 방식·저장 형태·컬럼 길이를 한꺼번에 결정한다. 관련 컬럼은 `image3d_url`·`image_main_url`·`performance_images.image_url` 모두 `varchar(255)`이고, prod는 `ddl-auto: validate`라 길이를 늘리려면 수동 DDL이 필요하다([#463](https://github.com/TicketRush/TicketRush-backend/issues/463)·[#422](https://github.com/TicketRush/TicketRush-backend/issues/422) 선례).

## 결정

### 1. 버킷 퍼블릭 읽기 정책으로 공개한다 (CloudFront를 두지 않는다)

버킷에 `s3:GetObject` 공개 정책을 걸고 객체 URL을 그대로 노출한다. 정책 형태는 `deploy/localstack/bucket-policy.example.json`에 둔다.

CloudFront + OAC가 캐싱·전송비에서 유리하지만, 배포 생성·OAC·도메인 설정이 전부 AWS 콘솔 작업이라 이 이슈가 인프라 작업에 묶인다. 대상은 공연 포스터와 캐릭터 GLB로 열람 통제가 필요한 자산이 아니다. CDN이 필요해지면 `app.s3.public-base-url` 하나를 CDN 도메인으로 바꾸는 것으로 전환한다.

### 2. DB에는 객체 키가 아니라 완성된 공개 URL을 저장한다

`https://{bucket}.s3.{region}.amazonaws.com/{prefix}/{UUID}.{ext}` 전체를 저장한다. 운영 버킷명 기준 최장 156자로 `varchar(255)`에 들어가므로 **스키마를 바꾸지 않는다**. presigned URL은 만료가 있어 저장 대상이 아니고, 서명 쿼리스트링 때문에 255를 넘긴다.

객체 키만 저장하고 응답에서 조립하는 안이 버킷 이관에는 강하다. 그러나 조립 지점이 늘어나는 것보다, **기존 row가 이미 전체 URL 형태라 마이그레이션이 필요 없다**는 점이 컸다. 시드 데이터와 스텁이 남긴 값이 모두 전체 URL이다.

### 3. 버킷명은 `app.s3.*`에서 읽고, 없으면 기동에 실패한다

awspring이 인식하지 않는 `spring.cloud.aws.s3.bucket` 대신 자체 네임스페이스 `app.s3.bucket`을 `@ConfigurationProperties` + `@Validated @NotBlank`로 읽는다. **버킷명 없이 기동에 성공하면 "등록은 201인데 파일은 저장되지 않는" 상태로 되돌아간다** — 이 이슈가 고치려는 바로 그 증상이다. 설정 누락은 관리자가 등록을 시도할 때가 아니라 배포 시점에 드러나야 한다.

단 `app.s3.public-base-url`에는 `@NotBlank`를 걸지 않는다. compose의 `env_file`은 `.env`의 빈 줄을 "정의된 빈 문자열"로 주입하는데 Spring의 `${VAR:default}`는 값이 null일 때만 기본값을 쓴다. 여기에 필수 제약을 걸면 "선택 항목이라 비워 둔" 운영자에게 기동 실패가 돌아간다([#490](https://github.com/TicketRush/TicketRush-backend/issues/490)에서 같은 계열의 함정을 겪었다). 비어 있으면 버킷·리전으로 조립한다.

### 4. 업로드 경로에 킬 스위치를 두지 않는다

스위치를 끈 상태가 곧 스텁 동작 — 파일이 저장되지 않는데 201이 나가는 상태 — 이므로, 되돌릴 대상이 아니라 없애야 할 상태다. S3 장애 시에는 등록을 실패시키는 것이 올바른 동작이며, 공연 등록은 관리자 저빈도 경로라 영향 범위가 좁다. 스키마를 건드리지 않으므로 롤백은 PR revert로 충분하다.

대신 실패를 **일시(재시도 가능)와 영구(설정 오류)로 나눈다.** 버킷 부재·권한 부족은 몇 번을 다시 눌러도 같은 실패이므로 503 "잠시 후 다시 시도해 주세요"로 안내하면 안 된다([#573](https://github.com/TicketRush/TicketRush-backend/issues/573)에서 PG 4xx를 원본 code로 재분류한 것과 같은 결).

### 5. 로컬은 LocalStack을 쓴다 (레포 최초)

킬 스위치가 없으므로 local 프로파일에서도 S3 엔드포인트가 실제로 필요하다. `docker-compose.yml`에 localstack을 추가하고 기동 시 버킷 생성·퍼블릭 정책 적용까지 자동화한다. awspring에 `spring.cloud.aws.s3.endpoint`와 `path-style-access-enabled`가 실재해 **코드 변경 없이 프로퍼티만으로** 붙는다.

## 결과

- 스키마 변경도, 데이터 마이그레이션도 없다. prod DDL 작업이 필요 없다.
- **prod 배포 전 `.env`에 `AWS_S3_BUCKET` 반영이 선행 조건이다.** 빠뜨리면 performance-service가 뜨지 않는다. 의도한 fail-fast지만, 배포 순서를 지켜야 한다.
- **버킷에 퍼블릭 읽기 정책을 걸어야 완료조건이 성립한다.** LocalStack 커뮤니티는 버킷 정책을 익명 접근에 강제하지 않으므로(정책을 걸지 않아도, Deny를 걸어도 익명 GET이 200) 통합 테스트가 이 회귀를 잡지 못한다. 운영자 수동 확인 항목이다.
- nginx `client_max_body_size`가 없으면 기본 1MB라 업로드가 앱에 도달하지 못한다. 레포의 `deploy/nginx/api.ticketrush.store.conf`에 `12m`을 넣었으나, nginx는 CD가 배포하지 않으므로 **EC2 실반영은 수동 작업이다**.
- `model3d`의 10MB 상한은 전역 multipart 상한과 같은 값이라 그 파트의 코드 검사는 현재 도달하지 않는다. 프론트의 GLB 실측 용량을 받으면 두 값을 함께 조정해야 한다.
- 파트별 검증·키 규칙(`FileKind`)이 [#637](https://github.com/TicketRush/TicketRush-backend/issues/637) 교체 API의 계약이 된다.
