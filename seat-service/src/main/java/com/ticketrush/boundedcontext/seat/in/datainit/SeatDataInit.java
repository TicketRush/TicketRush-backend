package com.ticketrush.boundedcontext.seat.in.datainit;

import com.ticketrush.boundedcontext.seat.domain.entity.Seat;
import com.ticketrush.boundedcontext.seat.domain.entity.SeatLayout;
import com.ticketrush.boundedcontext.seat.out.repository.SeatLayoutRepository;
import com.ticketrush.boundedcontext.seat.out.repository.SeatRepository;
import com.ticketrush.global.types.SeatStatus;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Profile({"local", "dev"})
@Component
@RequiredArgsConstructor
public class SeatDataInit implements ApplicationRunner {

  private final SeatRepository seatRepository;
  private final SeatLayoutRepository seatLayoutRepository;

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    // 1. 이미 데이터가 존재하면 실행하지 않음 (중복 방지)
    if (seatRepository.count() > 0) {
      log.info("좌석 더미 데이터가 이미 존재하여 초기화를 스킵합니다.");
      return;
    }

    Long performanceId = 1L; // 테스트용 공연 ID

    // 2. 공연의 '도면(Layout)'을 1개 생성
    SeatLayout masterLayout =
        SeatLayout.builder().performanceId(performanceId).totalRows(10).maxCols(12).build();
    SeatLayout savedLayout = seatLayoutRepository.save(masterLayout);

    // 3. 저장된 도면 정보를 읽어와서 좌석 생성 기준 설정
    int totalRows = savedLayout.getTotalRows();
    int maxCols = savedLayout.getMaxCols();

    List<Seat> seatsToSave = new ArrayList<>();

    // 4. 1번 도면을 참조하는 120개(10x12)의 낱개 좌석(Seat) 생성
    for (int i = 0; i < totalRows; i++) {
      char rowChar = (char) ('A' + i); // 0 -> A, 1 -> B ...

      for (int col = 1; col <= maxCols; col++) {
        String seatNumber = rowChar + "-" + col;

        Seat seat =
            Seat.builder()
                .seatLayoutId(savedLayout.getId())
                .performanceId(performanceId)
                .seatNumber(seatNumber)
                .seatStatus(SeatStatus.AVAILABLE)
                .holdExpiredAt(null)
                .build();

        seatsToSave.add(seat);
      }
    }

    // 5. 생성된 120개 좌석 일괄 DB 저장
    seatRepository.saveAll(seatsToSave);
    log.info(
        "도면 ID {}를 바탕으로 공연 ID {}의 더미 좌석 데이터 {}개가 모두 AVAILABLE 상태로 생성되었습니다.",
        savedLayout.getId(),
        performanceId,
        seatsToSave.size());
  }
}
