package com.ticketrush.boundedcontext.seat.app.usecase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.ticketrush.boundedcontext.seat.app.usecase.SeatReleaseExpiredChunkProcessor.ChunkResult;
import com.ticketrush.global.config.SeatReleaseProperties;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SeatReleaseExpiredUseCaseTest {

  private static final int CHUNK_SIZE = 100;
  private static final int MAX_CHUNKS = 3;

  @Mock private SeatReleaseExpiredChunkProcessor chunkProcessor;

  @Captor private ArgumentCaptor<LocalDateTime> nowCaptor;

  private SeatReleaseExpiredUseCase seatReleaseExpiredUseCase;

  @BeforeEach
  void setUp() {
    SeatReleaseProperties properties = new SeatReleaseProperties();
    properties.setChunkSize(CHUNK_SIZE);
    properties.setMaxChunks(MAX_CHUNKS);
    seatReleaseExpiredUseCase = new SeatReleaseExpiredUseCase(chunkProcessor, properties);
  }

  @Test
  @DisplayName("만료 좌석이 청크 크기 미만이면 한 번의 청크 처리로 종료한다")
  void execute_ReleasesSingleChunkAndStops() {
    // given
    given(chunkProcessor.releaseChunk(any(LocalDateTime.class), eq(CHUNK_SIZE)))
        .willReturn(new ChunkResult(30, 30));

    // when
    seatReleaseExpiredUseCase.execute();

    // then
    verify(chunkProcessor, times(1)).releaseChunk(any(LocalDateTime.class), eq(CHUNK_SIZE));
    verifyNoMoreInteractions(chunkProcessor);
  }

  @Test
  @DisplayName("만료 좌석이 없으면 한 번의 청크 처리로 종료한다")
  void execute_WhenNoExpiredSeats_StopsAfterSingleChunk() {
    // given: 첫 청크가 0건 반환
    given(chunkProcessor.releaseChunk(any(LocalDateTime.class), eq(CHUNK_SIZE)))
        .willReturn(new ChunkResult(0, 0));

    // when
    seatReleaseExpiredUseCase.execute();

    // then
    verify(chunkProcessor, times(1)).releaseChunk(any(LocalDateTime.class), eq(CHUNK_SIZE));
    verifyNoMoreInteractions(chunkProcessor);
  }

  @Test
  @DisplayName("청크가 가득 차면 다음 청크를 계속 처리하고, 청크 크기 미만이 나오면 종료한다")
  void execute_ProcessesMultipleChunksUntilDrained() {
    // given: 100, 100 처리 후 40에서 소진
    given(chunkProcessor.releaseChunk(any(LocalDateTime.class), eq(CHUNK_SIZE)))
        .willReturn(
            new ChunkResult(CHUNK_SIZE, CHUNK_SIZE),
            new ChunkResult(CHUNK_SIZE, CHUNK_SIZE),
            new ChunkResult(40, 40));

    // when
    seatReleaseExpiredUseCase.execute();

    // then
    verify(chunkProcessor, times(3)).releaseChunk(nowCaptor.capture(), eq(CHUNK_SIZE));
    verifyNoMoreInteractions(chunkProcessor);
    // 모든 청크에 동일한 기준 시각(now)이 전달되어야 한다(스냅샷 일관성).
    List<LocalDateTime> capturedNows = nowCaptor.getAllValues();
    assertThat(capturedNows).containsOnly(capturedNows.get(0));
  }

  @Test
  @DisplayName("청크가 계속 가득 차면 처리 상한(maxChunks)만큼만 처리하고 멈춘다")
  void execute_StopsAtMaxChunks() {
    // given: 매 청크가 가득 차 상한에 도달
    given(chunkProcessor.releaseChunk(any(LocalDateTime.class), eq(CHUNK_SIZE)))
        .willReturn(new ChunkResult(CHUNK_SIZE, CHUNK_SIZE));

    // when
    seatReleaseExpiredUseCase.execute();

    // then
    verify(chunkProcessor, times(MAX_CHUNKS))
        .releaseChunk(any(LocalDateTime.class), eq(CHUNK_SIZE));
    verifyNoMoreInteractions(chunkProcessor);
  }
}
