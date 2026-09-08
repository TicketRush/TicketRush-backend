package com.ticketrush.global.util;

import com.ticketrush.global.config.S3Properties;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import io.awspring.cloud.s3.ObjectMetadata;
import io.awspring.cloud.s3.S3Operations;
import io.awspring.cloud.s3.UploadFailedException;
import java.io.IOException;
import java.io.InputStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * 공연 파일을 S3에 올리고 외부 공개 URL을 돌려준다.
 *
 * <p>#636 이전에는 파일 내용을 어디에도 쓰지 않고 존재하지 않는 버킷의 가짜 URL 문자열만 만들어 반환했다. 업로드한 파일은 버려지는데 등록 API는 201을 반환해,
 * 저장이 되지 않는다는 사실이 응답만으로는 드러나지 않았다.
 *
 * <p><b>검증의 1차 책임은 호출부에 있다.</b> 파트별 확장자·크기 검사는 호출부가 업로드를 시작하기 전에 모든 파트에 대해 끝내야 한다. 업로드 직전에 파트마다
 * 검사하면 앞선 파트가 이미 올라간 뒤 뒤 파트에서 거절되어 매번 정리 대상이 생긴다.
 *
 * <p>그럼에도 {@code uploadFile}이 {@link FileKind#validate}를 한 번 더 부르는 이유: {@link
 * FileKind#newObjectKey}가 원본 파일명에서 확장자를 잘라 객체 키와 저장 URL에 그대로 넣는다. 선행 검증을 빠뜨린 새 호출부(#637 교체 API 등)가
 * 생기면 검증되지 않은 문자열이 키에 실린다. 검증은 멱등이고 비용이 없으므로, 안전성을 호출 순서라는 관례가 아니라 코드로 고정한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class S3UploadUtils {

  private final S3Operations s3Operations;
  private final S3Properties s3Properties;

  /**
   * 파일을 S3에 올리고 인증 없이 GET 할 수 있는 공개 URL을 반환한다.
   *
   * <p>업로드에 성공한 객체는 <b>트랜잭션이 커밋되지 않으면 자동으로 삭제</b>되도록 등록한다(아래 {@code registerCleanupOnFailure} 참고).
   */
  public String uploadFile(MultipartFile file, FileKind kind) {
    kind.validate(file);

    String objectKey = kind.newObjectKey(file);

    ObjectMetadata metadata =
        ObjectMetadata.builder()
            .contentType(FileKind.contentTypeOf(objectKey))
            .contentLength(file.getSize())
            .build();

    try (InputStream inputStream = file.getInputStream()) {
      s3Operations.upload(s3Properties.getBucket(), objectKey, inputStream, metadata);
    } catch (IOException | UploadFailedException | SdkException e) {
      if (isMisconfiguration(e)) {
        log.error(
            "S3 설정이 잘못되어 업로드에 실패했습니다. bucket={}, key={}", s3Properties.getBucket(), objectKey, e);

        throw new BusinessException(ErrorStatus.FILE_STORAGE_MISCONFIGURED, e);
      }

      log.error("S3 업로드에 실패했습니다. key={}", objectKey, e);

      throw new BusinessException(ErrorStatus.FILE_STORAGE_UNAVAILABLE, e);
    }

    registerCleanupOnFailure(objectKey);

    return s3Properties.toPublicUrl(objectKey);
  }

  /**
   * 트랜잭션이 커밋되지 않으면 이 객체를 지우도록 등록한다.
   *
   * <p>호출부의 try-catch가 아니라 트랜잭션 동기화를 쓰는 이유: 업로드가 모두 성공한 뒤 <b>커밋 단계</b>에서 실패하는 경로가 있다. 커밋은 호출부 메서드가
   * 리턴한 다음에 일어나므로 메서드 안의 catch로는 잡히지 않고, 그 경우 DB row는 없는데 객체만 남는다.
   *
   * <p><b>{@code STATUS_ROLLED_BACK}이 아니라 "커밋되지 않았을 때"로 판정하는 이유</b>: 그 커밋 단계 실패가 롤백으로 통지되지 않는다.
   * {@code AbstractPlatformTransactionManager.processCommit}은 {@code doCommit()}이 던진 예외를 {@code
   * rollbackOnCommitFailure}(기본 false)일 때 {@code STATUS_UNKNOWN}으로 통지한다. 예를 들어 flush-on-commit 단계의
   * 제약 위반은 {@code TransactionSystemException}으로 올라와 이 경로를 탄다. 롤백만 보고 정리하면 정작 이 콜백을 만든 이유였던 경로에서 객체가
   * 남는다.
   *
   * <p>정리 실패는 삼키고 로그만 남긴다. 여기서 예외를 올리면 원래의 실패 원인을 가려버린다 — 팀은 #333에서 같은 종류의 예외 마스킹으로 한 번 데었다.
   *
   * <p>트랜잭션 밖에서 호출되면 등록할 곳이 없어 고아를 되돌릴 수 없다. 현재 유일한 호출부인 공연 등록은 {@code @Transactional} 안이지만, 새
   * 호출부(#637 교체 API 등)가 트랜잭션 없이 부르면 조용히 새는 것을 막기 위해 경고를 남긴다.
   */
  private void registerCleanupOnFailure(String objectKey) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      log.warn("트랜잭션 밖에서 업로드했습니다. 실패 시 객체가 정리되지 않습니다. key={}", objectKey);

      return;
    }

    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            if (status != STATUS_COMMITTED) {
              deleteQuietly(objectKey);
            }
          }
        });
  }

  /**
   * 재시도해도 낫지 않는 설정 오류인지 판정한다.
   *
   * <p>버킷이 없거나(오타·미생성) IAM에 {@code s3:PutObject}가 없으면 몇 번을 다시 눌러도 같은 실패가 난다. 이걸 일시 장애와 같이 503으로 뭉개면
   * "잠시 후 다시 시도해 주세요"만 무한히 나가고, 설정이 틀렸다는 사실이 드러나지 않는다. 팀은 #573에서 PG 4xx를 원본 code로 재분류해 같은 문제를 풀었다.
   *
   * <p>원인을 거슬러 올라가며 보는 이유: awspring은 업로드 실패를 {@code UploadFailedException}으로 감싸 던지므로, 최상위 예외 타입만
   * 봐서는 SDK 예외를 놓친다.
   */
  private boolean isMisconfiguration(Throwable e) {
    for (Throwable cause = e; cause != null; cause = cause.getCause()) {
      if (cause instanceof NoSuchBucketException) {
        return true;
      }

      if (cause instanceof S3Exception s3Exception
          && s3Exception.statusCode() == HttpStatus.FORBIDDEN.value()) {
        return true;
      }
    }

    return false;
  }

  private void deleteQuietly(String objectKey) {
    try {
      s3Operations.deleteObject(s3Properties.getBucket(), objectKey);

      log.info("커밋되지 않아 업로드 객체를 삭제했습니다. key={}", objectKey);
    } catch (RuntimeException e) {
      log.error("업로드 객체 삭제에 실패했습니다. 고아 객체가 남습니다. key={}", objectKey, e);
    }
  }
}
