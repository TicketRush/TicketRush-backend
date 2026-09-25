package com.ticketrush.boundedcontext.performance.app.usecase;

import com.ticketrush.boundedcontext.performance.app.dto.request.PerformanceFileReplaceRequest;
import com.ticketrush.boundedcontext.performance.app.dto.response.PerformanceFileReplaceResponse;
import com.ticketrush.boundedcontext.performance.domain.entity.Performance;
import com.ticketrush.boundedcontext.performance.out.repository.PerformanceRepository;
import com.ticketrush.global.constants.CacheConstants;
import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;
import com.ticketrush.global.util.FileKind;
import com.ticketrush.global.util.S3UploadUtils;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 등록된 공연의 파일을 교체한다(#637). #688부터는 파일 파트 외에 {@code request} 파트의 변경 지시(갤러리 유지 목록·3D 모델 비우기)도 받는다.
 *
 * <p>이전에는 파일 URL을 쓰는 경로가 {@code Performance.updateUrls()} 하나뿐이었고 그마저 등록 유스케이스에서만 불려서, 3D 모델을 바꾸려면
 * 공연을 지우고 다시 등록하는 수밖에 없었다. 그러면 {@code performanceId}가 바뀌어 좌석·예매가 함께 끊긴다.
 */
@Service
@RequiredArgsConstructor
public class PerformanceReplaceFilesUseCase {

  private final S3UploadUtils s3UploadUtils;
  private final PerformanceRepository performanceRepository;

  /**
   * 전달된 파트만 S3에 올리고 공연의 파일 URL을 갈아끼운다. 보내지 않은 파트는 기존 URL을 그대로 둔다.
   *
   * <p><b>{@code @Transactional}이 이 메서드의 계약이다.</b> {@link S3UploadUtils}는 업로드한 객체를 트랜잭션 동기화에 걸어 롤백
   * 시 지우는데, 트랜잭션 밖에서 불리면 예외가 아니라 경고 로그만 남기고 통과한다. 이 어노테이션이 빠지면 실패한 요청마다 S3에 고아 객체가 조용히 쌓인다. 대부분의
   * 테스트는 스스로 {@code @Transactional}이라 그 부재를 가려주므로, 비트랜잭션인 {@code
   * PerformanceListCacheTest.replaceFiles_uploadsInsideTransaction}이 이 계약을 지키는 유일한 안전망이다.
   *
   * <p><b>순서가 계약이다.</b>
   *
   * <ol>
   *   <li>공연 조회 — 없으면 404. 존재하지 않는 공연에 대해서는 페이로드가 유효한지 따질 의미가 없고, 검증·업로드를 아예 시작하지 않는다.
   *   <li>파트·지시 검증 — 등록 경로(#636)와 같이 <b>업로드를 시작하기 전에</b> 전부 끝낸다. 업로드 도중에 검사하면 앞선 파트가 이미 올라간 뒤 뒤
   *       파트에서 거절되어, 거절될 요청마다 정리 대상이 생긴다. 완료 조건 "검증에 걸리면 기존 URL이 그대로 남는다"가 이 순서로 충족된다. 유지 목록 검증이 현재
   *       갤러리를 읽어야 해서 조회가 검증보다 앞선다는 순서도 여기서 정해진다.
   *   <li>업로드 → 엔티티 반영.
   * </ol>
   *
   * <p><b>갤러리의 최종 목록은 request 파트 유무로 갈린다(#688).</b> {@code keep_gallery_urls}를 보내지 않으면 #637 그대로다 —
   * 새 갤러리 파일이 있으면 그것만으로 전체 치환, 없으면 유지. 보내면 "유지 목록(그 순서대로) + 새 파일"이 최종 목록이고, 빈 목록에 새 파일이 없으면 갤러리가
   * 비워진다. 유지 목록 없이 빈 갤러리 파트가 들어온 경우는 null로 바꿔 넘겨 도메인이 갤러리를 건드리지 않게 한다 — 도메인의 빈 목록 가드가 #688에서 걷혔으므로
   * 이 변환이 #637 계약을 지키는 유일한 방어다.
   *
   * <p><b>교체·비우기로 참조를 잃은 기존 S3 객체는 지우지 않는다.</b> 지우면 그 URL을 캐싱하고 있던 클라이언트가 즉시 깨지고, 남기면 고아 객체가 쌓일 뿐
   * 사용자에게 보이는 영향이 없다. 판단이 갈리면 사용자에게 보이지 않는 쪽으로 실패시킨다는 #636의 기준({@code STATUS_UNKNOWN}일 때 업로드 객체를 남긴
   * 것)과 같다. 누적 정리가 필요해지면 별도 이슈로 다룬다.
   *
   * <p>목록 응답에 {@code imageMainUrl}이 있어 메인 이미지를 바꾸면 목록이 stale이 되므로, 다른 변경 유스케이스들과 같은 규약으로 목록 캐시를 전량
   * 비운다.
   *
   * <p><b>트랜잭션이 최대 5회(메인 1 + 모델 1 + 갤러리 3, 합계 최대 25MB)의 S3 왕복 전 구간 동안 열려 있어 DB 커넥션을 점유한다.</b> 업로드를
   * 트랜잭션 밖으로 빼면 롤백 시 정리할 방법이 없어지므로 위 계약과 맞바꿀 수 없다. 관리자 전용 저빈도 API라 현재는 감수하지만, 파트가 늘거나 호출 빈도가 올라가면
   * 커넥션 풀에 먼저 영향이 온다.
   *
   * @param request 변경 지시. 파트를 보내지 않으면 null이며 그때는 #637 계약 그대로 동작한다.
   */
  @CacheEvict(cacheNames = CacheConstants.PERFORMANCE_LIST_CACHE, allEntries = true)
  @Transactional
  public PerformanceFileReplaceResponse execute(
      Long performanceId,
      MultipartFile mainImage,
      MultipartFile model3d,
      List<MultipartFile> gallery,
      PerformanceFileReplaceRequest request) {

    Performance performance =
        performanceRepository
            .findById(performanceId)
            .orElseThrow(() -> new BusinessException(ErrorStatus.PERFORMANCE_NOT_FOUND));

    List<MultipartFile> galleryFiles = validate(performance, mainImage, model3d, gallery, request);

    String mainImageUrl =
        hasFile(mainImage) ? s3UploadUtils.uploadFile(mainImage, FileKind.MAIN_IMAGE) : null;
    String model3dUrl =
        hasFile(model3d) ? s3UploadUtils.uploadFile(model3d, FileKind.MODEL_3D) : null;
    List<String> newGalleryUrls =
        galleryFiles.stream()
            .map(file -> s3UploadUtils.uploadFile(file, FileKind.GALLERY))
            .toList();

    if (clearsModel3d(request)) {
      performance.clearModel3d();
    }
    performance.replaceFiles(mainImageUrl, model3dUrl, resolveGalleryUrls(request, newGalleryUrls));

    return PerformanceFileReplaceResponse.from(performance);
  }

  /**
   * 파트와 변경 지시를 검증하고, 실제로 업로드할 갤러리 파일 목록을 돌려준다.
   *
   * <p><b>변경 지시가 하나도 없으면 거절한다.</b> 지시는 실제 파일이 있는 파트, 유지 목록(빈 배열 포함), {@code clear_model3d: true} 셋
   * 중 하나다. 아무것도 안 하고 성공을 돌려주면 멀티파트에서 가장 흔한 클라이언트 버그인 <b>파트 이름 오타</b>가 은폐된다 — Spring은 이름이 다른 파트를 조용히
   * 무시하고 null로 바인딩하므로, 프론트는 200을 받고도 파일이 바뀌지 않은 이유를 찾을 수 없다. {@code request} 파트가 {@code {}}나 {@code
   * clear_model3d: false}뿐이면 지시가 아니라 같은 이유로 거절한다.
   *
   * <p><b>비어 있는 파트는 보내지 않은 것과 같이 본다.</b> 브라우저는 파일을 고르지 않은 {@code <input type="file">}도 {@code
   * filename=""}인 0바이트 파트로 실어 보내므로, 빈 파트를 {@code FILE_EMPTY}로 거절하면 "파일 입력 세 개 중 하나만 골랐다"는 가장 흔한 폼에서
   * 요청 전체가 400이 된다. 그러면 "보낸 파트만 교체한다"는 이 API의 계약이 깨진다. 갤러리도 같은 기준으로 빈 파일을 먼저 걷어낸다.
   *
   * <p><b>유지 목록 검증 순서.</b> 모순(비우기 + 새 모델 파일)·중복은 클라이언트 버그라 DB를 보지 않고도 판정되므로 먼저 거절하고, 현재 갤러리에 없는
   * URL은 다른 어드민이 먼저 바꾼 stale 화면 신호라 그다음이다. 개수 상한은 유지 목록 단독이 아니라 <b>유지 + 신규 파일의 합</b>으로 센다 — 유지 목록을
   * 보내지 않았으면 #637 그대로 신규 파일 수만 센다. 그 뒤에 파일 형식·크기를 본다.
   */
  private List<MultipartFile> validate(
      Performance performance,
      MultipartFile mainImage,
      MultipartFile model3d,
      List<MultipartFile> gallery,
      PerformanceFileReplaceRequest request) {

    List<MultipartFile> galleryFiles =
        (gallery == null)
            ? List.of()
            : gallery.stream().filter(PerformanceReplaceFilesUseCase::hasFile).toList();

    boolean hasFilePart = hasFile(mainImage) || hasFile(model3d) || !galleryFiles.isEmpty();
    boolean hasInstruction =
        request != null && (request.hasKeepGalleryUrls() || request.clearsModel3d());

    if (!hasFilePart && !hasInstruction) {
      throw new BusinessException(ErrorStatus.PERFORMANCE_NO_FILE_TO_REPLACE);
    }

    if (clearsModel3d(request) && hasFile(model3d)) {
      throw new BusinessException(ErrorStatus.PERFORMANCE_MODEL3D_CLEAR_CONFLICT);
    }

    int keepCount = 0;
    if (request != null && request.hasKeepGalleryUrls()) {
      List<String> keepUrls = request.keepGalleryUrls();

      if (new HashSet<>(keepUrls).size() != keepUrls.size()) {
        throw new BusinessException(ErrorStatus.PERFORMANCE_KEEP_GALLERY_URL_DUPLICATED);
      }
      if (!performance.getImageGalleryUrls().containsAll(keepUrls)) {
        throw new BusinessException(ErrorStatus.PERFORMANCE_KEEP_GALLERY_URL_NOT_FOUND);
      }
      keepCount = keepUrls.size();
    }

    if (keepCount + galleryFiles.size() > FileKind.GALLERY_MAX_COUNT) {
      throw new BusinessException(ErrorStatus.PERFORMANCE_GALLERY_LIMIT_EXCEEDED);
    }

    if (hasFile(mainImage)) {
      FileKind.MAIN_IMAGE.validate(mainImage);
    }

    if (hasFile(model3d)) {
      FileKind.MODEL_3D.validate(model3d);
    }

    galleryFiles.forEach(FileKind.GALLERY::validate);

    return galleryFiles;
  }

  /**
   * 도메인에 넘길 최종 갤러리 목록을 만든다. null은 "갤러리를 건드리지 않는다"이고, 빈 목록을 포함한 목록은 "그 목록으로 치환한다"이다.
   *
   * <p>유지 목록이 없으면(#637 경로) 새 파일이 없을 때 null을 돌려준다. 빈 목록을 그대로 넘기면 도메인이 갤러리를 비워 버리므로 이 분기가 #637 계약을
   * 지킨다.
   */
  private static List<String> resolveGalleryUrls(
      PerformanceFileReplaceRequest request, List<String> newGalleryUrls) {

    if (request == null || !request.hasKeepGalleryUrls()) {
      return newGalleryUrls.isEmpty() ? null : newGalleryUrls;
    }

    List<String> finalUrls = new ArrayList<>(request.keepGalleryUrls());
    finalUrls.addAll(newGalleryUrls);

    return finalUrls;
  }

  private static boolean clearsModel3d(PerformanceFileReplaceRequest request) {
    return request != null && request.clearsModel3d();
  }

  private static boolean hasFile(MultipartFile file) {
    return file != null && !file.isEmpty();
  }
}
