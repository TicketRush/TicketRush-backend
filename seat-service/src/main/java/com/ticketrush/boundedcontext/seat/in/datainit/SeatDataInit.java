package com.ticketrush.boundedcontext.seat.in.datainit;

import com.ticketrush.boundedcontext.seat.app.facade.SeatFacade;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Slf4j
@Profile("local")
@Component
@RequiredArgsConstructor
public class SeatDataInit implements ApplicationRunner {

  private static final Long DUMMY_PERFORMANCE_ID = 1L;

  private final SeatFacade seatFacade;

  @Override
  public void run(ApplicationArguments args) {
    // null이면 기본 좌석 수로 생성한다(#590). local 더미 공연은 좌석 수에 의미가 없다.
    seatFacade.createDefaultSeats(DUMMY_PERFORMANCE_ID, null);
    log.info("local 프로필용 더미 공연 좌석 초기화를 요청했습니다. performanceId: {}", DUMMY_PERFORMANCE_ID);
  }
}
