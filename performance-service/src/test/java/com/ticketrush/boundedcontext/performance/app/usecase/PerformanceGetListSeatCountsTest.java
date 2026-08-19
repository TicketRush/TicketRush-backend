package com.ticketrush.boundedcontext.performance.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.ticketrush.boundedcontext.performance.app.dto.response.PerformanceListResponse;
import com.ticketrush.boundedcontext.performance.app.mapper.PerformanceMapper;
import com.ticketrush.boundedcontext.performance.app.mapper.PerformanceMapperImpl;
import com.ticketrush.boundedcontext.performance.domain.entity.Performance;
import com.ticketrush.boundedcontext.performance.domain.types.Genre;
import com.ticketrush.boundedcontext.performance.domain.types.PerformanceStatus;
import com.ticketrush.boundedcontext.performance.out.apiclient.SeatRestClient;
import com.ticketrush.boundedcontext.performance.out.apiclient.dto.SeatCountsInfo;
import com.ticketrush.boundedcontext.performance.out.repository.PerformanceRepository;
import com.ticketrush.global.dto.request.CursorPageRequest;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.SliceImpl;

/** 공개 공연 목록의 게이지바용 좌석 수 합성 (#176). */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PerformanceGetListSeatCountsTest {

  @Mock private PerformanceRepository performanceRepository;
  @Mock private SeatRestClient seatRestClient;

  /**
   * 매퍼는 <b>실제 구현체</b>다. mock으로 두면 매퍼의 {@code ignore} 방어가 풀려도 이 테스트들이 전부 통과한다.
   *
   * <p><b>그 방어를 실제로 지키는 것은 좌석 수를 "모르는" 두 테스트</b>({@code
   * execute_seatLookupFailed_omitsOnlySeatFields} ·{@code
   * execute_seatsNotCreatedYet_omitsSeatFields})<b>다.</b> 좌석 수를 받아 오는 테스트들은 유스케이스가 값을 덮어쓰기 때문에 방어가
   * 풀려도 통과한다 — 실제로 {@code ignore}를 지우고 돌리면 이 둘만 깨진다. 이름만 보고 앞쪽 테스트를 방어로 착각해 둘을 중복으로 지우면 방어가 사라진다.
   */
  private final PerformanceMapper performanceMapper = new PerformanceMapperImpl();

  private PerformanceGetListUseCase useCase;

  private static final CursorPageRequest PAGE = new CursorPageRequest(null, 10);

  @BeforeEach
  void setUp() {
    useCase =
        new PerformanceGetListUseCase(performanceRepository, performanceMapper, seatRestClient);
  }

  @Test
  @DisplayName("게이지바의 분모는 좌석 서비스 실측값이며, 공연 등록 시 입력한 총 좌석 수가 아니다")
  void execute_usesSeatServiceCounts_notEntityTotalSeats() {
    // given: 엔티티에 적힌 총 좌석은 120석이지만 좌석 서비스가 실제로 만든 좌석은 500석이다
    givenPage(performance(1L, 120));
    givenSeatCounts(Map.of(1L, new SeatCountsInfo(1L, 500L, 300L)));

    // when
    PerformanceListResponse row = firstRow();

    // then
    assertThat(row.totalSeats()).isEqualTo(500L);
    assertThat(row.remainingSeats()).isEqualTo(200L);
  }

  @Test
  @DisplayName("좌석 서비스 조회에 실패하면 좌석 필드만 비우고 공연 정보는 그대로 내린다")
  void execute_seatLookupFailed_omitsOnlySeatFields() {
    givenPage(performance(1L, 120));
    givenSeatCounts(Map.of()); // fail-open: 빈 맵

    PerformanceListResponse row = firstRow();

    assertThat(row.totalSeats()).isNull();
    assertThat(row.remainingSeats()).isNull();
    assertThat(row.performanceId()).isEqualTo(1L);
    assertThat(row.title()).isEqualTo("공연 1");
    assertThat(row.price()).isEqualTo(50_000L);
    assertThat(row.performanceStatus()).isEqualTo(PerformanceStatus.ON_SALE);
  }

  @Test
  @DisplayName("좌석이 아직 생성되지 않은 공연은 0석이 아니라 필드 생략으로 구분된다")
  void execute_seatsNotCreatedYet_omitsSeatFields() {
    // given: 좌석 서비스는 정상이지만 이 공연의 좌석은 아직 생성 전이라 맵에 키가 없다
    givenPage(performance(1L, 120));
    givenSeatCounts(Map.of(99L, new SeatCountsInfo(99L, 500L, 300L)));

    PerformanceListResponse row = firstRow();

    assertThat(row.totalSeats()).isNull();
    assertThat(row.remainingSeats()).isNull();
  }

  @Test
  @DisplayName("매진된 공연은 잔여 0석을 내린다 — 좌석 수를 모르는 경우와 구분된다")
  void execute_soldOut_returnsZeroInsteadOfOmitting() {
    givenPage(performance(1L, 120));
    givenSeatCounts(Map.of(1L, new SeatCountsInfo(1L, 500L, 500L)));

    PerformanceListResponse row = firstRow();

    assertThat(row.totalSeats()).isEqualTo(500L);
    assertThat(row.remainingSeats()).isZero();
  }

  @Test
  @DisplayName("좌석 서비스 호출은 페이지당 한 번이고, 현재 페이지의 공연 ID만 전달한다")
  void execute_callsSeatServiceOncePerPage() {
    givenPage(performance(1L, 120), performance(2L, 120));
    givenSeatCounts(Map.of());

    useCase.execute(null, null, null, null, PAGE);

    ArgumentCaptor<List<Long>> captor = ArgumentCaptor.captor();
    verify(seatRestClient, times(1)).getSeatCounts(captor.capture());
    assertThat(captor.getValue()).containsExactly(1L, 2L);
  }

  @Test
  @DisplayName("좌석 수를 얹어도 공연 정보 필드는 그대로 보존된다")
  void execute_composingSeatCounts_preservesOtherFields() {
    // 좌석 값을 얹는 복사가 위치 기반이라, String 필드끼리 자리가 바뀌어도 컴파일은 통과한다.
    // 값을 서로 다르게 두어 뒤바뀜을 잡는다.
    givenPage(performance(1L, 120));
    givenSeatCounts(Map.of(1L, new SeatCountsInfo(1L, 500L, 300L)));

    PerformanceListResponse row = firstRow();

    assertThat(row.performanceId()).isEqualTo(1L);
    assertThat(row.title()).isEqualTo("공연 1");
    assertThat(row.performer()).isEqualTo("가수");
    assertThat(row.genre()).isEqualTo(Genre.CONCERT);
    assertThat(row.showDate()).isEqualTo(LocalDate.of(2026, 9, 1));
    assertThat(row.showTime()).isEqualTo(LocalTime.of(19, 0));
    assertThat(row.address()).isEqualTo("서울");
    assertThat(row.imageMainUrl()).isEqualTo("https://s3.example.com/main.jpg");
    assertThat(row.performanceStatus()).isEqualTo(PerformanceStatus.ON_SALE);
    assertThat(row.price()).isEqualTo(50_000L);
  }

  @Test
  @DisplayName("필터·커서가 걸린 요청에서도 좌석 수를 합성한다 — 캐시를 타는 요청만의 기능이 아니다")
  void execute_filteredRequest_alsoComposesSeatCounts() {
    givenPage(performance(1L, 120));
    givenSeatCounts(Map.of(1L, new SeatCountsInfo(1L, 500L, 300L)));

    PerformanceListResponse row =
        useCase
            .execute(
                Genre.CONCERT,
                10_000L,
                90_000L,
                PerformanceStatus.ON_SALE,
                new CursorPageRequest(5L, 10))
            .content()
            .get(0);

    assertThat(row.totalSeats()).isEqualTo(500L);
    assertThat(row.remainingSeats()).isEqualTo(200L);
    verify(seatRestClient, times(1)).getSeatCounts(anyList());
  }

  @Test
  @DisplayName("공연이 한 건도 없으면 좌석 서비스에 빈 목록을 넘긴다")
  void execute_emptyPage_passesEmptyIdList() {
    givenPage();
    givenSeatCounts(Map.of());

    assertThat(useCase.execute(null, null, null, null, PAGE).content()).isEmpty();

    // 빈 목록을 그대로 넘기는 것이 계약이다 — 클라이언트가 HTTP 없이 끊는다.
    // 여기서 임의로 호출을 건너뛰면 그 가드가 어디에 있는지 흐려진다.
    ArgumentCaptor<List<Long>> captor = ArgumentCaptor.captor();
    verify(seatRestClient).getSeatCounts(captor.capture());
    assertThat(captor.getValue()).isEmpty();
  }

  private PerformanceListResponse firstRow() {
    return useCase.execute(null, null, null, null, PAGE).content().get(0);
  }

  private void givenPage(Performance... performances) {
    given(performanceRepository.findByFilters(any(), any(), any(), any(), any(), anyInt()))
        .willReturn(new SliceImpl<>(List.of(performances), PageRequest.of(0, PAGE.size()), false));
  }

  private void givenSeatCounts(Map<Long, SeatCountsInfo> counts) {
    given(seatRestClient.getSeatCounts(anyList())).willReturn(counts);
  }

  /** {@code entityTotalSeats}는 공연 등록 시 입력값이다 — 게이지바가 이 값을 쓰면 안 된다. */
  private Performance performance(Long id, Integer entityTotalSeats) {
    Performance performance = mock(Performance.class);
    given(performance.getId()).willReturn(id);
    given(performance.getTitle()).willReturn("공연 " + id);
    given(performance.getPerformer()).willReturn("가수");
    given(performance.getGenre()).willReturn(Genre.CONCERT);
    given(performance.getShowDate()).willReturn(LocalDate.of(2026, 9, 1));
    given(performance.getShowTime()).willReturn(LocalTime.of(19, 0));
    given(performance.getAddress()).willReturn("서울");
    given(performance.getImageMainUrl()).willReturn("https://s3.example.com/main.jpg");
    given(performance.getPerformanceStatus()).willReturn(PerformanceStatus.ON_SALE);
    given(performance.getPrice()).willReturn(50_000L);
    given(performance.getTotalSeats()).willReturn(entityTotalSeats);
    return performance;
  }
}
