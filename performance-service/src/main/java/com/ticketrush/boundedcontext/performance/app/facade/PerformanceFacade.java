package com.ticketrush.boundedcontext.performance.app.facade;

import com.ticketrush.boundedcontext.performance.app.dto.request.PerformanceCreateRequest;
import com.ticketrush.boundedcontext.performance.app.dto.response.PerformanceCreateResponse;
import com.ticketrush.boundedcontext.performance.app.dto.response.PerformanceListResponse;
import com.ticketrush.boundedcontext.performance.app.usecase.PerformanceCreateUseCase;
import com.ticketrush.boundedcontext.performance.app.usecase.PerformanceGetListUseCase;
import com.ticketrush.boundedcontext.performance.domain.types.Genre;
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

  public PerformanceCreateResponse createPerformance(
      PerformanceCreateRequest request,
      MultipartFile mainImage,
      MultipartFile model3d,
      List<MultipartFile> gallery) {

    return performanceCreateUseCase.execute(request, mainImage, model3d, gallery);
  }

  public Page<PerformanceListResponse> getPerformances(Genre genre, Pageable pageable) {
    return performanceGetListUseCase.execute(genre, pageable);
  }
}
