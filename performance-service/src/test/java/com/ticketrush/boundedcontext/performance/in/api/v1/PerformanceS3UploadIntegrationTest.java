package com.ticketrush.boundedcontext.performance.in.api.v1;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketrush.boundedcontext.performance.app.dto.request.PerformanceCreateRequest;
import com.ticketrush.boundedcontext.performance.app.dto.response.PerformanceCreateResponse;
import com.ticketrush.boundedcontext.performance.app.usecase.PerformanceCreateUseCase;
import com.ticketrush.boundedcontext.performance.domain.types.Genre;
import com.ticketrush.boundedcontext.performance.out.repository.PerformanceRepository;
import com.ticketrush.global.eventpublisher.EventPublisher;
import com.ticketrush.global.util.FileKind;
import com.ticketrush.global.util.S3UploadUtils;
import io.awspring.cloud.s3.S3Operations;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 공연 등록 파일이 <b>실제로</b> 오브젝트 스토리지에 저장되고, 저장된 URL로 원본이 그대로 다시 읽히는지 검증한다(#636 완료조건 1·3·7).
 *
 * <p>mock으로는 이 이슈가 고치려는 버그를 잡을 수 없다 — 스텁 구현도 "URL 문자열을 반환한다"는 계약은 지켰고, 등록 API는 201을 반환했다. 저장이 되지
 * 않는다는 사실은 실제 스토리지에 물어봐야만 드러난다. 그래서 레포 최초로 LocalStack을 쓴다.
 *
 * <p>다른 공연 테스트들과 달리 {@code S3AutoConfiguration}을 제외하지 않는다. 실제 {@link S3Operations} 빈이 필요하고, 접속 정보는
 * {@link DynamicPropertySource}로 컨테이너를 가리키게 덮는다.
 *
 * <p><b>이 테스트가 증명하지 못하는 것: 완료조건 2의 "인증 없이 GET 된다".</b> LocalStack 커뮤니티는 S3 버킷 정책을 익명 접근에 강제하지 않는다 —
 * 정책을 걸지 않아도, 심지어 Deny 정책을 걸어도 익명 GET이 200으로 돌아온다(실측). 따라서 아래 익명 GET 단언이 증명하는 것은 <b>업로드한 바이트가 그대로
 * 다시 읽힌다</b>는 데까지이며, 실제 S3에서 퍼블릭 읽기 정책이 빠져 403이 나는 회귀는 잡지 못한다. 버킷 정책은 배포 체크리스트(운영자 수동 확인) 항목이다 —
 * {@code deploy/localstack/bucket-policy.example.json}.
 *
 * <p>Docker가 필요하다. 꺼져 있으면 {@code open -a Docker}로 켠 뒤 실행한다.
 */
@Testcontainers
@SpringBootTest
@ActiveProfiles("test")
class PerformanceS3UploadIntegrationTest {

  private static final String BUCKET = "ticket-rush-test-bucket";

  @Container
  static LocalStackContainer localstack =
      new LocalStackContainer(DockerImageName.parse("localstack/localstack:4.13.1"))
          .withServices("s3");

  @DynamicPropertySource
  static void s3Properties(DynamicPropertyRegistry registry) {
    registry.add("spring.cloud.aws.s3.endpoint", () -> localstack.getEndpoint().toString());
    // 가상 호스트 방식(bucket.s3.amazonaws.com)은 컨테이너 주소로 풀리지 않는다.
    registry.add("spring.cloud.aws.s3.path-style-access-enabled", () -> "true");
    registry.add("spring.cloud.aws.credentials.access-key", localstack::getAccessKey);
    registry.add("spring.cloud.aws.credentials.secret-key", localstack::getSecretKey);
    registry.add("spring.cloud.aws.region.static", localstack::getRegion);

    registry.add("app.s3.bucket", () -> BUCKET);
    registry.add("app.s3.public-base-url", PerformanceS3UploadIntegrationTest::publicBaseUrl);
  }

  /**
   * 버킷을 만들고 퍼블릭 읽기 정책을 건다.
   *
   * <p>정책은 운영 버킷에 걸 것과 같은 형태다({@code deploy/localstack/bucket-policy.example.json}). 다만 LocalStack이
   * 이 정책을 익명 접근에 강제하지 않으므로(클래스 javadoc 참고) 정책을 거는 것은 <b>운영과 같은 형태로 셋업한다</b>는 의미이지 통과 조건이 아니다.
   *
   * <p>종료 코드를 단언하는 이유: 확인하지 않으면 버킷 생성이나 정책 적용이 실패해도 조용히 넘어가고, 뒤의 업로드가 엉뚱한 이유로 실패해 원인을 찾기 어려워진다.
   */
  @BeforeAll
  static void createBucket() throws IOException, InterruptedException {
    execOrFail("awslocal", "s3", "mb", "s3://" + BUCKET);

    String policy =
        """
        {"Version":"2012-10-17","Statement":[{"Sid":"PublicReadGetObject","Effect":"Allow",\
        "Principal":"*","Action":"s3:GetObject","Resource":"arn:aws:s3:::%s/*"}]}\
        """
            .formatted(BUCKET);

    execOrFail("awslocal", "s3api", "put-bucket-policy", "--bucket", BUCKET, "--policy", policy);
  }

  private static void execOrFail(String... command) throws IOException, InterruptedException {
    var result = localstack.execInContainer(command);

    assertThat(result.getExitCode())
        .withFailMessage(
            "LocalStack 준비 명령이 실패했습니다: %s%n%s", String.join(" ", command), result.getStderr())
        .isZero();
  }

  @MockitoBean private EventPublisher eventPublisher;

  @Autowired private PerformanceCreateUseCase performanceCreateUseCase;
  @Autowired private PerformanceRepository performanceRepository;
  @Autowired private S3UploadUtils s3UploadUtils;
  @Autowired private S3Operations s3Operations;
  @Autowired private TransactionTemplate transactionTemplate;

  @Test
  @DisplayName("등록한 GLB·이미지가 실제로 저장되고, 응답 URL을 인증 없이 GET 하면 원본이 내려온다")
  void storesFilesAndServesThemPublicly() throws Exception {
    byte[] glbBytes = "glb-binary-content".getBytes();
    byte[] posterBytes = "poster-image-content".getBytes();
    byte[] galleryBytes = "gallery-image-content".getBytes();

    MockMultipartFile mainImage =
        new MockMultipartFile("mainImage", "poster.png", "image/png", posterBytes);
    MockMultipartFile model3d =
        new MockMultipartFile("model3d", "character.glb", "model/gltf-binary", glbBytes);
    List<MultipartFile> gallery =
        List.of(new MockMultipartFile("gallery", "g1.jpg", "image/jpeg", galleryBytes));

    PerformanceCreateResponse response =
        performanceCreateUseCase.execute(request(), mainImage, model3d, gallery);

    // 갤러리는 LAZY @ElementCollection 이라 findById 로 읽으면 세션 밖에서 터진다.
    // 이 테스트는 실제 커밋을 확인해야 해서 @Transactional 을 걸 수 없으므로 fetch join 쪽을 쓴다.
    var saved = performanceRepository.findDetailById(response.performanceId()).orElseThrow();

    // 저장된 URL로 다시 읽으면 업로드한 GLB 원본 바이트가 그대로 내려온다.
    // (익명 접근 자체의 강제는 LocalStack이 하지 않는다 — 클래스 javadoc 참고)
    HttpResponse<byte[]> model3dResponse = getAnonymously(saved.getImage3dUrl());

    assertThat(model3dResponse.statusCode()).isEqualTo(200);
    assertThat(model3dResponse.body()).isEqualTo(glbBytes);
    assertThat(model3dResponse.headers().firstValue("content-type")).contains("model/gltf-binary");

    // mainImage·gallery도 같은 방식으로 저장되고 조회된다.
    assertThat(getAnonymously(saved.getImageMainUrl()).body()).isEqualTo(posterBytes);
    assertThat(getAnonymously(saved.getImageGalleryUrls().get(0)).body()).isEqualTo(galleryBytes);

    // 저장된 값은 조립 가능한 공개 URL이다(객체 키나 presigned URL이 아니다).
    assertThat(saved.getImage3dUrl()).startsWith(publicBaseUrl() + "/");
    assertThat(saved.getImage3dUrl()).doesNotContain("?");
    assertThat(saved.getImage3dUrl()).hasSizeLessThanOrEqualTo(255);
  }

  /**
   * 5MB를 넘는 파일이 실제로 올라가고 그대로 내려오는지 확인한다.
   *
   * <p>awspring의 기본 {@code InMemoryBufferingS3OutputStreamProvider}는 버퍼 임계치를 넘으면 단일 {@code
   * putObject}가 아니라 {@code createMultipartUpload}/{@code uploadPart}/{@code
   * completeMultipartUpload} 경로로 갈라진다. 이 이슈의 실제 대상인 캐릭터 GLB가 그 경로를 타므로, 작은 파일만 태우면 정작 쓰일 분기를 한 번도
   * 지나가지 않는다.
   *
   * <p>6MB인 이유는 S3 멀티파트의 최소 파트 크기가 5MB이고 버퍼 임계치가 그 값을 따르기 때문이다. 다만 이 테스트는 <b>어느 경로로 올라갔는지를 단언하지는
   * 않는다</b> — 기본 provider가 바뀌거나 임계치가 6MB 위로 올라가면 단일 putObject로 조용히 되돌아가고도 통과한다. 그때는 이 주석의 전제부터 다시
   * 확인해야 한다.
   */
  @Test
  @DisplayName("5MB를 넘는 GLB도 멀티파트 경로로 저장되고 원본 그대로 내려온다")
  void storesLargeModel3dThroughMultipartUpload() throws Exception {
    byte[] largeGlb = new byte[6 * 1024 * 1024];

    for (int i = 0; i < largeGlb.length; i++) {
      largeGlb[i] = (byte) (i % 251);
    }

    MockMultipartFile model3d =
        new MockMultipartFile("model3d", "large.glb", "model/gltf-binary", largeGlb);

    String url =
        transactionTemplate.execute(status -> s3UploadUtils.uploadFile(model3d, FileKind.MODEL_3D));

    HttpResponse<byte[]> downloaded = getAnonymously(url);

    assertThat(downloaded.statusCode()).isEqualTo(200);
    assertThat(downloaded.body()).isEqualTo(largeGlb);
  }

  @Test
  @DisplayName("트랜잭션이 롤백되면 이미 올라간 객체를 지운다")
  void deletesUploadedObjectOnRollback() {
    MockMultipartFile model3d =
        new MockMultipartFile("model3d", "orphan.glb", "model/gltf-binary", "x".getBytes());

    String uploadedUrl =
        transactionTemplate.execute(
            status -> {
              String url = s3UploadUtils.uploadFile(model3d, FileKind.MODEL_3D);

              status.setRollbackOnly();

              return url;
            });

    String objectKey = objectKeyOf(uploadedUrl);

    assertThat(s3Operations.objectExists(BUCKET, objectKey)).isFalse();
  }

  @Test
  @DisplayName("트랜잭션이 커밋되면 객체는 남는다")
  void keepsUploadedObjectOnCommit() {
    MockMultipartFile model3d =
        new MockMultipartFile("model3d", "kept.glb", "model/gltf-binary", "x".getBytes());

    String uploadedUrl =
        transactionTemplate.execute(status -> s3UploadUtils.uploadFile(model3d, FileKind.MODEL_3D));

    assertThat(s3Operations.objectExists(BUCKET, objectKeyOf(uploadedUrl))).isTrue();
  }

  /** path-style 접근 기준의 공개 base URL. {@code getEndpoint()}는 끝에 슬래시를 붙이지 않는다. */
  private static String publicBaseUrl() {
    return localstack.getEndpoint() + "/" + BUCKET;
  }

  private String objectKeyOf(String publicUrl) {
    return publicUrl.substring((publicBaseUrl() + "/").length());
  }

  private HttpResponse<byte[]> getAnonymously(String url) throws Exception {
    return HttpClient.newHttpClient()
        .send(
            HttpRequest.newBuilder(URI.create(url)).build(),
            HttpResponse.BodyHandlers.ofByteArray());
  }

  private PerformanceCreateRequest request() {
    return PerformanceCreateRequest.builder()
        .title("S3 연동 공연")
        .performer("가수")
        .genre(Genre.MUSICAL)
        .description("설명")
        .showDate(LocalDate.now())
        .showTime(LocalTime.of(19, 0))
        .durationMinutes(120)
        .price(50000L)
        .totalSeats(100)
        .address("서울")
        .bookingOpenAt(LocalDateTime.of(2025, 8, 1, 20, 0))
        .build();
  }
}
