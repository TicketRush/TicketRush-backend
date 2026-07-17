package com.ticketrush.global.util;

import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/** S3 파일 업로드 관련 유틸리티 클래스 현재는 AWS 미연결 상태로 가짜 URL을 반환하도록 구현됨 */
@Component
public class S3UploadUtils {

  /*
   * TODO: AWS S3 연동 시 활성화 필요
   * 1. AmazonS3 의존성 주입
   * 2. @Value("${cloud.aws.s3.bucket}") bucketName 주입
   */

  private static final List<String> ALLOWED_EXTENSIONS =
      Arrays.asList("jpg", "jpeg", "png", "glb", "obj");

  /** 파일을 검증하고 가짜 S3 URL을 생성하여 반환합니다. */
  public String uploadFile(MultipartFile file) {
    if (file == null || file.isEmpty()) {
      // 혜림님 리뷰 반영: BAD_REQUEST -> 상세 에러 코드로 변경
      throw new BusinessException(ErrorStatus.FILE_EMPTY);
    }

    String originalFilename = file.getOriginalFilename();
    String extension = validateExtension(originalFilename);

    String s3FileName = UUID.randomUUID().toString() + "." + extension;

    // 가짜 URL 반환 (AWS 연결 전까지 사용)
    return "https://ticketrush-fake-s3.s3.ap-northeast-2.amazonaws.com/temp/" + s3FileName;
  }

  private String validateExtension(String filename) {
    if (filename == null || !filename.contains(".")) {
      throw new BusinessException(ErrorStatus.FILE_INVALID_EXTENSION);
    }

    String extension = filename.substring(filename.lastIndexOf(".") + 1).toLowerCase();

    if (!ALLOWED_EXTENSIONS.contains(extension)) {
      throw new BusinessException(ErrorStatus.FILE_EXTENSION_NOT_ALLOWED);
    }
    return extension;
  }
}
