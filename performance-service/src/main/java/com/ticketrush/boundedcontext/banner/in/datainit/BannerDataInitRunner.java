package com.ticketrush.boundedcontext.banner.in.datainit;

import com.ticketrush.boundedcontext.banner.domain.entity.Banner;
import com.ticketrush.boundedcontext.banner.out.repository.BannerRepository;
import java.time.LocalDate;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로컬 배너 시드 (#564).
 *
 * <p><b>공연 시드({@code DataInitRunner})에 얹지 않고 러너를 따로 둔 이유는 스킵 가드 때문이다.</b> 공연 시드는 {@code
 * performanceRepository.count() > 0}이면 통째로 건너뛰므로, 거기에 배너를 넣으면 이미 공연 데이터가 있는 기존 로컬 DB에는 배너가 영영 들어가지
 * 않는다.
 *
 * <p>문구는 프론트 mock({@code src/api/mocks/banners.ts})과 일치시켰다. 프론트가 {@code USE_MOCK}을 끄고 이 API로 전환할 때
 * 화면이 그대로여야 계약이 맞는지 눈으로 확인할 수 있다. <b>날짜만 mock과 다르다</b> — mock 값(2026-06-15/07-20/08-10)이 이미 지난 날짜라
 * 그대로 쓰면 지난 공연을 광고하는 배너가 되기 때문이며, prod 시드도 같은 날짜를 쓴다({@code Banner} javadoc의 런북).
 */
@Slf4j
@Profile("local")
@Component
@RequiredArgsConstructor
public class BannerDataInitRunner implements ApplicationRunner {

  private final BannerRepository bannerRepository;

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    if (bannerRepository.count() > 0) {
      log.info("[DataInit] 배너 데이터가 이미 존재합니다. 초기화를 건너뜁니다.");
      return;
    }

    List<Banner> banners =
        List.of(
            Banner.builder()
                .title("Summer Jazz Night")
                .subtitle("여름밤의 재즈 향연")
                .description("세계적인 재즈 뮤지션과 함께하는 특별한 밤")
                .tagLabel("조기 예매 할인")
                .iconEmoji("🎵")
                .displayDate(LocalDate.of(2026, 9, 15))
                .displayOrder(1)
                .build(),
            Banner.builder()
                .title("K-Pop Mega Concert")
                .subtitle("최고의 아이돌 스타 집결")
                .description("팬들이 기다린 드림 라인업 공개!")
                .tagLabel("VIP 패키지 판매중")
                .iconEmoji("🎤")
                .displayDate(LocalDate.of(2026, 10, 20))
                .displayOrder(2)
                .build(),
            Banner.builder()
                .title("Classical Gala")
                .subtitle("클래식의 정수를 만나다")
                .description("세계 3대 오케스트라 내한 공연")
                .tagLabel("프리미엄 좌석 한정")
                .iconEmoji("🎹")
                .displayDate(LocalDate.of(2026, 11, 10))
                .displayOrder(3)
                .build());

    bannerRepository.saveAll(banners);
    log.info("[DataInit] 샘플 배너 데이터 {}건 초기화 완료.", banners.size());
  }
}
