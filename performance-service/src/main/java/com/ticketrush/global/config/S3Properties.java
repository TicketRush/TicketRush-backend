package com.ticketrush.global.config;

import jakarta.annotation.PostConstruct;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * 공연 파일 업로드용 S3 설정.
 *
 * <p><b>{@code spring.cloud.aws.s3.bucket}을 쓰지 않는 이유</b>: 그 키는 awspring 4.0.0이 인식하는 프로퍼티가 아니다.
 * {@code spring-cloud-aws-autoconfigure}의 configuration metadata에 {@code bucket} 키가 없어(같은 이유로
 * {@code spring.cloud.aws.stack.auto}도 2.x 잔재다) 어떤 빈도 바인딩하지 않는다. #636 이전까지 prod에 {@code
 * AWS_S3_BUCKET}이 비어 있어도 아무 증상이 없었던 것이 그 때문이다. 그래서 자체 네임스페이스로 읽는다.
 *
 * <p><b>{@code @Validated}로 fail-fast 하는 이유</b>: 버킷명 없이 기동에 성공하면 "등록은 201인데 파일은 저장되지 않는" 상태가 된다 —
 * #636이 고치려는 바로 그 증상이다. 설정 누락은 관리자가 등록을 시도할 때가 아니라 배포 시점에 드러나야 한다. 따라서 <b>prod 배포 전 {@code .env}에
 * {@code AWS_S3_BUCKET} 반영이 선행 조건</b>이며, 빠뜨리면 서비스가 뜨지 않는다.
 *
 * <p>{@link #publicBaseUrl}을 별도로 받는 이유는, local이 LocalStack({@code http://localhost:4566/...})을 쓰고
 * 나중에 CloudFront를 붙일 때도 이 값만 바꾸면 되기 때문이다. <b>비어 있으면 버킷·리전으로 조립</b>하므로 운영에서 굳이 지정할 필요는 없다.
 *
 * <p><b>{@code publicBaseUrl}에는 {@code @NotBlank}를 걸지 않는다.</b> {@code .env}에 {@code
 * AWS_S3_PUBLIC_BASE_URL=}처럼 빈 줄이 있으면 compose의 {@code env_file}이 그것을 "정의된 빈 문자열"로 컨테이너에 넣는데,
 * Spring의 {@code ${VAR:default}}는 값이 null일 때만 기본값을 쓰므로 빈 문자열은 기본값으로 접히지 않는다. 여기에 {@code @NotBlank}를
 * 걸면 "선택 항목이라 비워 뒀을 뿐"인 운영자에게 <b>기동 실패</b>가 돌아간다. 팀은 #490에서 같은 계열의 함정(prod 환경변수 미정의를 못 잡아 전건 503)을
 * 겪었다.
 */
@Slf4j
@Getter
@Setter
@Component
@Validated
@ConfigurationProperties(prefix = "app.s3")
public class S3Properties {

  /** 업로드 대상 버킷 이름. */
  @NotBlank private String bucket;

  /** 버킷이 위치한 리전. 공개 URL 조립에 쓴다. */
  @NotBlank private String region;

  /**
   * 저장된 객체를 인증 없이 GET 할 수 있는 base URL. 객체 키 앞에 붙는다. 끝의 {@code /}는 있어도 없어도 된다.
   *
   * <p>비워 두면 {@code https://{bucket}.s3.{region}.amazonaws.com}으로 조립한다. LocalStack이나 CloudFront처럼
   * 호스트가 다를 때만 지정한다.
   */
  private String publicBaseUrl;

  /**
   * {@code spring.cloud.aws.s3.endpoint}를 그대로 받은 값. 경고 판정에만 쓴다.
   *
   * <p>LocalStack이나 MinIO처럼 endpoint를 AWS가 아닌 곳으로 덮은 상태에서 {@link #publicBaseUrl}을 빠뜨리면, 조립 결과가 실제
   * 저장 위치와 무관한 AWS 주소가 된다. 업로드 자체는 성공해 등록 API가 201을 반환하므로, URL이 존재하지 않는 호스트를 가리킨다는 사실이 프론트에서 이미지가 안
   * 뜰 때까지 드러나지 않는다 — #636이 고치려던 증상과 같은 모양이다.
   */
  private String endpointOverride;

  /** base URL과 객체 키를 이어 붙여 외부 공개 URL을 만든다. */
  public String toPublicUrl(String objectKey) {
    String base = resolveBaseUrl();

    return (base.endsWith("/") ? base : base + "/") + objectKey;
  }

  @PostConstruct
  void warnIfBaseUrlLooksWrong() {
    if (endpointOverride != null && !endpointOverride.isBlank() && isPublicBaseUrlBlank()) {
      log.warn(
          "S3 endpoint 를 {} 로 덮었는데 app.s3.public-base-url 이 비어 있습니다. "
              + "저장되는 URL 은 {} 형태가 되어 실제 저장 위치를 가리키지 않습니다.",
          endpointOverride,
          resolveBaseUrl());
    }
  }

  private boolean isPublicBaseUrlBlank() {
    return publicBaseUrl == null || publicBaseUrl.isBlank();
  }

  private String resolveBaseUrl() {
    if (isPublicBaseUrlBlank()) {
      return "https://" + bucket + ".s3." + region + ".amazonaws.com";
    }

    return publicBaseUrl;
  }
}
