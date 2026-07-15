package com.ticketrush.boundedcontext.performance.in.api.v1;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.ticketrush.boundedcontext.performance.app.dto.request.PerformanceCreateRequest;
import com.ticketrush.boundedcontext.performance.app.dto.response.PerformanceCreateResponse;
import com.ticketrush.boundedcontext.performance.app.usecase.PerformanceCreateUseCase;
import com.ticketrush.boundedcontext.performance.domain.types.Genre;
import com.ticketrush.boundedcontext.performance.out.repository.PerformanceRepository;
import com.ticketrush.global.eventpublisher.EventPublisher;
import com.ticketrush.global.util.S3UploadUtils;
import com.ticketrush.shared.performance.event.PerformanceCreatedEvent;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@SpringBootTest
@ActiveProfiles("test")
@EnableAutoConfiguration(
    exclude = {
      io.awspring.cloud.autoconfigure.s3.S3AutoConfiguration.class,
      io.awspring.cloud.autoconfigure.core.AwsAutoConfiguration.class
    })
@Transactional
class PerformanceCreateTest {

  @MockitoBean private S3UploadUtils s3UploadUtils;
  @MockitoBean private EventPublisher eventPublisher;

  @Autowired private PerformanceCreateUseCase performanceCreateUseCase;

  @Autowired private PerformanceRepository performanceRepository;

  @Test
  @DisplayName("공연 등록 시 이미지 및 3D 모델 URL이 DB에 정상적으로 저장되어야 한다")
  void createPerformanceTest() {
    PerformanceCreateRequest request =
        PerformanceCreateRequest.builder()
            .title("콘서트")
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

    MockMultipartFile mainImage =
        new MockMultipartFile("mainImage", "test.png", "image/png", "content".getBytes());
    MockMultipartFile model3d =
        new MockMultipartFile(
            "model3d", "test.glb", "application/octet-stream", "content".getBytes());
    List<MultipartFile> gallery =
        List.of(new MockMultipartFile("gallery", "g1.png", "image/png", "content".getBytes()));

    String expectedMainUrl = "https://s3.main-image.png";
    String expectedModelUrl = "https://s3.test-model.glb";
    String expectedGalleryUrl = "https://s3.gallery-1.png";

    given(s3UploadUtils.uploadFile(mainImage)).willReturn(expectedMainUrl);
    given(s3UploadUtils.uploadFile(model3d)).willReturn(expectedModelUrl);
    given(s3UploadUtils.uploadFile(gallery.get(0))).willReturn(expectedGalleryUrl);

    PerformanceCreateResponse response =
        performanceCreateUseCase.execute(request, mainImage, model3d, gallery);

    assertThat(response).isNotNull();

    var savedPerformance = performanceRepository.findById(response.performanceId()).orElseThrow();

    assertThat(savedPerformance.getTitle()).isEqualTo(request.title());
    assertThat(savedPerformance.getBookingOpenAt()).isEqualTo(request.bookingOpenAt());

    assertThat(savedPerformance.getImageMainUrl()).isEqualTo(expectedMainUrl);
    assertThat(savedPerformance.getImage3dUrl()).isEqualTo(expectedModelUrl);
    assertThat(savedPerformance.getImageGalleryUrls()).hasSize(1);
    assertThat(savedPerformance.getImageGalleryUrls().get(0)).isEqualTo(expectedGalleryUrl);

    ArgumentCaptor<PerformanceCreatedEvent> captor =
        ArgumentCaptor.forClass(PerformanceCreatedEvent.class);
    then(eventPublisher).should().publish(captor.capture());
    PerformanceCreatedEvent publishedEvent = captor.getValue();
    assertThat(publishedEvent.performanceId()).isEqualTo(savedPerformance.getId());
    assertThat(publishedEvent.title()).isEqualTo(request.title());
    assertThat(publishedEvent.totalSeats()).isEqualTo(request.totalSeats());
    assertThat(publishedEvent.showDate()).isEqualTo(request.showDate());
    assertThat(publishedEvent.showTime()).isEqualTo(request.showTime());
    assertThat(publishedEvent.price()).isEqualTo(request.price());
  }
}
