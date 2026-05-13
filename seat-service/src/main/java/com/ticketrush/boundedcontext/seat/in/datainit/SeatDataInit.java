package com.ticketrush.boundedcontext.seat.in.datainit;

import com.ticketrush.boundedcontext.seat.app.usecase.SeatCreateDefaultLayoutUseCase;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Slf4j
@Profile({"local", "dev"})
@Component
@RequiredArgsConstructor
public class SeatDataInit implements ApplicationRunner {

  private static final Long DUMMY_PERFORMANCE_ID = 1L;

  private final SeatCreateDefaultLayoutUseCase seatCreateDefaultLayoutUseCase;

  @Override
  public void run(ApplicationArguments args) {
    seatCreateDefaultLayoutUseCase.execute(DUMMY_PERFORMANCE_ID);
    log.info("local/dev 프로필용 더미 공연 좌석 초기화를 요청했습니다. performanceId: {}", DUMMY_PERFORMANCE_ID);
  }
}
