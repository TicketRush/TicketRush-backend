package com.ticketrush.boundedcontext.performance.in.datainit;

import com.ticketrush.boundedcontext.performance.domain.entity.Performance;
import com.ticketrush.boundedcontext.performance.domain.types.Genre;
import com.ticketrush.boundedcontext.performance.out.repository.PerformanceRepository;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Profile("local")
@Component
@RequiredArgsConstructor
public class DataInitRunner implements ApplicationRunner {

  private final PerformanceRepository performanceRepository;

  private static final String PLACEHOLDER_MAIN_IMAGE = "https://placehold.co/1200x800.jpg";
  private static final String PLACEHOLDER_MODEL_3D = "https://placehold.co/placeholder.glb";
  private static final String PLACEHOLDER_GALLERY = "https://placehold.co/800x600.jpg";

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    if (performanceRepository.count() > 0) {
      log.info("[DataInit] 공연 데이터가 이미 존재합니다. 초기화를 건너뜁니다.");
      return;
    }

    List<Performance> performances =
        List.of(
            Performance.builder()
                .title("레미제라블")
                .performer("류정한, 카이, 민경아")
                .genre(Genre.MUSICAL)
                .description("빅토르 위고의 불후의 명작을 무대 위에 펼쳐내는 뮤지컬 레미제라블. 자유와 혁명, 사랑과 구원의 이야기.")
                .showDate(LocalDate.of(2025, 7, 5))
                .showTime(LocalTime.of(19, 30))
                .durationMinutes(170)
                .price(140000L)
                .totalSeats(1200)
                .address("서울특별시 중구 장충단로 59 국립극장 해오름극장")
                .imageMainUrl(PLACEHOLDER_MAIN_IMAGE)
                .image3dUrl(PLACEHOLDER_MODEL_3D)
                .imageGalleryUrls(List.of(PLACEHOLDER_GALLERY, PLACEHOLDER_GALLERY))
                .facilities(List.of("주차장", "수유실", "장애인석", "물품보관소"))
                .build(),
            Performance.builder()
                .title("BTS World Tour 2025 : CONNECT")
                .performer("BTS")
                .genre(Genre.CONCERT)
                .description("BTS의 글로벌 월드 투어 공연. 서울 잠실을 시작으로 전 세계를 연결하는 특별한 무대.")
                .showDate(LocalDate.of(2025, 8, 15))
                .showTime(LocalTime.of(19, 0))
                .durationMinutes(150)
                .price(165000L)
                .totalSeats(70000)
                .address("서울특별시 송파구 올림픽로 25 잠실종합운동장")
                .imageMainUrl(PLACEHOLDER_MAIN_IMAGE)
                .image3dUrl(PLACEHOLDER_MODEL_3D)
                .imageGalleryUrls(
                    List.of(PLACEHOLDER_GALLERY, PLACEHOLDER_GALLERY, PLACEHOLDER_GALLERY))
                .facilities(List.of("주차장", "푸드코트", "장애인석", "응급의료소"))
                .build(),
            Performance.builder()
                .title("서울시향 브람스 교향곡 전집")
                .performer("오스모 벤스케 지휘 / 서울시립교향악단")
                .genre(Genre.CLASSIC)
                .description("핀란드 출신의 세계적인 지휘자 오스모 벤스케와 서울시향이 선보이는 브람스 교향곡 전곡 시리즈.")
                .showDate(LocalDate.of(2025, 9, 12))
                .showTime(LocalTime.of(20, 0))
                .durationMinutes(120)
                .price(50000L)
                .totalSeats(2523)
                .address("서울특별시 서초구 서초대로 60 예술의전당 콘서트홀")
                .imageMainUrl(PLACEHOLDER_MAIN_IMAGE)
                .image3dUrl(PLACEHOLDER_MODEL_3D)
                .imageGalleryUrls(List.of(PLACEHOLDER_GALLERY))
                .facilities(List.of("주차장", "레스토랑", "물품보관소"))
                .build(),
            Performance.builder()
                .title("재즈 페스타 서울 2025")
                .performer("이정식 밴드, 나윤선, 말로")
                .genre(Genre.JAZZ)
                .description("서울 한복판에서 펼쳐지는 최고의 재즈 나이트. 국내 최정상 재즈 아티스트들이 한 자리에 모입니다.")
                .showDate(LocalDate.of(2025, 10, 3))
                .showTime(LocalTime.of(18, 0))
                .durationMinutes(180)
                .price(75000L)
                .totalSeats(3000)
                .address("서울특별시 마포구 월드컵북로 400 상암 문화광장")
                .imageMainUrl(PLACEHOLDER_MAIN_IMAGE)
                .image3dUrl(PLACEHOLDER_MODEL_3D)
                .imageGalleryUrls(List.of(PLACEHOLDER_GALLERY, PLACEHOLDER_GALLERY))
                .facilities(List.of("주차장", "푸드트럭", "장애인석"))
                .build(),
            Performance.builder()
                .title("2025 그린플러그드 서울")
                .performer("잔나비, 10cm, 적재, 요조, 검정치마")
                .genre(Genre.FESTIVAL)
                .description("도심 속 자연 공원에서 즐기는 감성 뮤직 페스티벌. 인디부터 팝까지 다채로운 라인업.")
                .showDate(LocalDate.of(2025, 5, 17))
                .showTime(LocalTime.of(12, 0))
                .durationMinutes(480)
                .price(99000L)
                .totalSeats(10000)
                .address("서울특별시 마포구 성산동 월드컵공원 평화의광장")
                .imageMainUrl(PLACEHOLDER_MAIN_IMAGE)
                .image3dUrl(PLACEHOLDER_MODEL_3D)
                .imageGalleryUrls(
                    List.of(PLACEHOLDER_GALLERY, PLACEHOLDER_GALLERY, PLACEHOLDER_GALLERY))
                .facilities(List.of("주차장", "화장실", "의무실", "물품보관소", "푸드코트"))
                .build(),
            Performance.builder()
                .title("국립발레단 호두까기 인형")
                .performer("국립발레단")
                .genre(Genre.BALLET)
                .description("차이콥스키의 명작 발레 '호두까기 인형'. 국립발레단이 선보이는 크리스마스 시즌 특별 공연.")
                .showDate(LocalDate.of(2025, 12, 20))
                .showTime(LocalTime.of(15, 0))
                .durationMinutes(110)
                .price(80000L)
                .totalSeats(1700)
                .address("서울특별시 서초구 남부순환로 2406 예술의전당 오페라극장")
                .imageMainUrl(PLACEHOLDER_MAIN_IMAGE)
                .image3dUrl(PLACEHOLDER_MODEL_3D)
                .imageGalleryUrls(List.of(PLACEHOLDER_GALLERY, PLACEHOLDER_GALLERY))
                .facilities(List.of("주차장", "수유실", "장애인석", "레스토랑"))
                .build(),
            Performance.builder()
                .title("아이브 팬미팅 : I'VE MINE 2025")
                .performer("아이브 (IVE)")
                .genre(Genre.FANMEETING)
                .description("아이브와 직접 만나는 특별한 팬미팅. 토크, 게임, 미니 콘서트로 가득한 팬들을 위한 하루.")
                .showDate(LocalDate.of(2025, 11, 8))
                .showTime(LocalTime.of(17, 0))
                .durationMinutes(120)
                .price(110000L)
                .totalSeats(5000)
                .address("서울특별시 강남구 영동대로 513 코엑스 홀 D")
                .imageMainUrl(PLACEHOLDER_MAIN_IMAGE)
                .image3dUrl(PLACEHOLDER_MODEL_3D)
                .imageGalleryUrls(List.of(PLACEHOLDER_GALLERY, PLACEHOLDER_GALLERY))
                .facilities(List.of("주차장", "물품보관소", "포토존", "MD판매"))
                .build());

    performanceRepository.saveAll(performances);
    log.info("[DataInit] 샘플 공연 데이터 {}건 초기화 완료.", performances.size());
  }
}
