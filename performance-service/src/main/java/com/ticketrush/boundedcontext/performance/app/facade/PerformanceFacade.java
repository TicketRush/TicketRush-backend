package com.ticketrush.boundedcontext.performance.app.facade;

import com.ticketrush.boundedcontext.performance.app.dto.request.PerformanceChangeStatusRequest;
import com.ticketrush.boundedcontext.performance.app.dto.request.PerformanceCreateRequest;
import com.ticketrush.boundedcontext.performance.app.dto.request.PerformancePatchRequest;
import com.ticketrush.boundedcontext.performance.app.dto.response.PerformanceCreateResponse;
import com.ticketrush.boundedcontext.performance.app.dto.response.PerformanceDetailResponse;
import com.ticketrush.boundedcontext.performance.app.dto.response.PerformanceListResponse;
import com.ticketrush.boundedcontext.performance.app.usecase.PerformanceChangeStatusUseCase;
import com.ticketrush.boundedcontext.performance.app.usecase.PerformanceCreateUseCase;
import com.ticketrush.boundedcontext.performance.app.usecase.PerformanceDeleteUseCase;
import com.ticketrush.boundedcontext.performance.app.usecase.PerformanceGetDetailUseCase;
import com.ticketrush.boundedcontext.performance.app.usecase.PerformanceGetListUseCase;
import com.ticketrush.boundedcontext.performance.app.usecase.PerformancePatchUseCase;
import com.ticketrush.boundedcontext.performance.app.usecase.PerformanceValidateUseCase;
import com.ticketrush.boundedcontext.performance.domain.types.Genre;
import com.ticketrush.boundedcontext.performance.domain.types.PerformanceStatus;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
@RequiredArgsConstructor
public class PerformanceFacade {

  private final PerformanceCreateUseCase performanceCreateUseCase;
  private final PerformanceGetListUseCase performanceGetListUseCase;
  private final PerformanceGetDetailUseCase performanceGetDetailUseCase;
  private final PerformanceChangeStatusUseCase performanceChangeStatusUseCase;
  private final PerformancePatchUseCase performancePatchUseCase;
  private final PerformanceDeleteUseCase performanceDeleteUseCase;
  private final PerformanceValidateUseCase performanceValidateUseCase;

  public PerformanceCreateResponse createPerformance(
      PerformanceCreateRequest request,
      MultipartFile mainImage,
      MultipartFile model3d,
      List<MultipartFile> gallery) {

    return performanceCreateUseCase.execute(request, mainImage, model3d, gallery);
  }

  public Page<PerformanceListResponse> getPerformances(
      Genre genre, Long minPrice, Long maxPrice, PerformanceStatus status, Pageable pageable) {
    return performanceGetListUseCase.execute(genre, minPrice, maxPrice, status, pageable);
  }

  public PerformanceDetailResponse getPerformanceDetail(Long performanceId) {
    return performanceGetDetailUseCase.execute(performanceId);
  }

  public void changePerformanceStatus(Long performanceId, PerformanceChangeStatusRequest request) {
    performanceChangeStatusUseCase.execute(performanceId, request);
  }

  public void patchPerformance(Long performanceId, PerformancePatchRequest request) {
    performancePatchUseCase.execute(performanceId, request);
  }

  public void deletePerformance(Long performanceId) {
    performanceDeleteUseCase.execute(performanceId);
  }

  public void validatePerformance(Long performanceId) {
    performanceValidateUseCase.execute(performanceId);
  }
}
