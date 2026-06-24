# 🗺️ MapStruct 기반 Mapper 구조 도입 가이드

본 문서는 우리 프로젝트에 MapStruct를 도입하여 엔티티(Entity)와 DTO 간의 변환을 효율적이고 일관성 있게 처리하기 위한 가이드라인입니다.

## 1. 개요 및 목적
MapStruct는 Java Bean 간의 매핑을 위한 코드를 자동으로 생성해 주는 라이브러리입니다.
개발자가 반복적인 `Getter`/`Setter` 코드를 작성할 필요 없이, 인터페이스에 매핑 규칙만 정의하면 컴파일 시점에 구현체를 자동으로 생성해 주어 **생산성 향상**과 **타입 안정성**을 보장합니다.

## 2. 패키지 위치 및 네이밍 규칙
* **위치:** Mapper 인터페이스는 해당 모듈의 `app` 디렉토리 하위 `mapper` 디렉토리에 위치합니다.
    * 예시: `com.ticketrush.boundedcontext.seat.app.mapper`
* **네이밍:** `{DomainName}Mapper` 형태로 작성합니다.
    * 예시: `SeatMapper`, `UserMapper`

## 3. 기본 구현 가이드

### 3.1. 의존성 추가
```groovy
    // mapstruct
    implementation 'org.mapstruct:mapstruct:1.5.5.Final'
    annotationProcessor "org.projectlombok:lombok-mapstruct-binding:0.2.0"
    annotationProcessor 'org.mapstruct:mapstruct-processor:1.5.5.Final'
    testAnnotationProcessor 'org.mapstruct:mapstruct-processor:1.5.5.Final'
```

### 3.2. 단일 소스 매핑 (Single Source)
하나의 객체를 다른 하나의 객체로 변환하는 가장 기본적인 형태입니다. 필드명이 동일하면 자동으로 매핑되며, 다를 경우 `@Mapping` 애너테이션으로 명시합니다.

**[예시: Entity -> DTO / DTO -> Entity]**
```java
@Mapper(componentModel = "spring") // Spring 컨텍스트에서 의존성 주입
public interface SeatMapper {
    
    // 1. Entity -> DTO 변환 (DB의 seat_id를 DTO의 seatId로 매핑)
    @Mapping(source = "id", target = "seatId")
    SeatDetailResponse toSeatDetailResponse(Seat seat);

    // 2. DTO -> Entity 변환 (클라이언트 요청을 Entity로 변환)
    // DTO에 없는 필드(예: id, 상태값 등)는 무시하거나 기본값을 설정할 수 있습니다.
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "seatStatus", constant = "AVAILABLE")
    Seat toEntity(SeatCreateRequest request);
}
```

### 3.3. 다중 소스 매핑 (Multiple Source)
두 개 이상의 엔티티, 객체, 또는 파라미터를 조합하여 하나의 대상 객체로 변환해야 할 때 사용합니다. 매핑 대상이 명확하도록 `source`에 객체명과 필드명을 함께 명시합니다.

**[예시: 복수 파라미터 -> DTO / 복수 파라미터 -> Entity]**
```java
@Mapper(componentModel = "spring")
public interface SeatMapper {

    // 1. 복수 Entity -> DTO 변환
    @Mapping(source = "seat.id", target = "seatId")
    @Mapping(source = "seatLayout.id", target = "seatLayoutId")
    @Mapping(source = "seatLayout.rowNo", target = "rowNo")
    @Mapping(source = "seatLayout.colNo", target = "colNo")
    SeatLayoutResponse toSeatLayoutResponse(Seat seat, SeatLayout seatLayout);

    // 2. DTO + 추가 파라미터 -> Entity 변환
    @Mapping(target = "id", ignore = true)
    @Mapping(source = "performanceId", target = "performanceId")
    @Mapping(source = "request.seatNumber", target = "seatNumber")
    Seat toEntity(SeatCreateRequest request, Long performanceId);
}
```

### 3.4. 컴파일 시점 구현체 자동 생성 원리
MapStruct는 Java의 **Annotation Processor**를 활용합니다. 빌드(컴파일) 시점에 `@Mapper`가 붙은 인터페이스를 스캔하고, 정의된 매핑 규칙을 바탕으로 순수 Java 코드로 이루어진 구현체(`~Impl.class`)를 `build/generated/sources/annotationProcessor` 폴더 하위에 자동으로 생성합니다.

**[MapStruct가 자동으로 생성해 주는 구현체 예시 (SeatMapperImpl.java)]**

```java
@Component // componentModel = "spring" 옵션에 의해 자동 추가됨
public class SeatMapperImpl implements SeatMapper {

    @Override
    public SeatDetailResponse toSeatDetailResponse(Seat seat) {
        if (seat == null) {
            return null;
        }

        SeatDetailResponse.SeatDetailResponseBuilder response = SeatDetailResponse.builder();

        // 개발자가 작성할 Getter/Setter 코드를 컴파일러가 대신 생성
        response.seatId(seat.getId());
        response.seatNumber(seat.getSeatNumber());
        // ... (나머지 필드 매핑)

        return response.build();
    }
}
```

리플렉션(Reflection)을 사용하는 다른 매퍼 라이브러리와 달리 일반적인 메서드 호출 코드를 직접 생성하기 때문에 **실행 속도가 압도적으로 빠르고 디버깅이 쉽습니다.**

### 3.5. Mapper 활용 방법
`componentModel = "spring"` 옵션을 주었기 때문에, 생성된 구현체는 스프링 컨테이너에 의해 빈(Bean)으로 관리됩니다. 따라서 `UseCase`, `Service`, `Facade` 등의 계층에서 생성자 주입을 받아 바로 사용할 수 있습니다.

**[UseCase에서의 활용 예시]**
```java
@Service
@RequiredArgsConstructor
public class SeatCreateUseCase {

    private final SeatRepository seatRepository;
    private final SeatMapper seatMapper; // 생성자 주입 방식으로 Mapper 주입

    @Transactional
    public SeatDetailResponse createSeat(Long performanceId, SeatCreateRequest request) {
        // 1. DTO와 식별자를 결합하여 Mapper를 통해 Entity로 변환
        Seat seat = seatMapper.toEntity(request, performanceId);
        
        // 2. DB 저장
        Seat savedSeat = seatRepository.save(seat);
        
        // 3. 저장된 Entity를 Mapper를 통해 응답 DTO로 변환하여 반환
        return seatMapper.toSeatDetailResponse(savedSeat);
    }
}
```

---

## 4. 🚨 [중요] Mapper 사용 시 주의 사항 및 역할 분리

MapStruct가 편리하다고 해서 **모든 DTO 변환에 Mapper를 사용해야 하는 것은 아닙니다.**

### ❌ Mapper가 필요 없는 경우: DB에서 DTO로 직접 조회 (Projection)
Repository 단계에서 JPQL이나 QueryDSL을 사용해 데이터를 조회할 때, 영속성 컨텍스트를 거치지 않고 바로 DTO로 변환(Projection)하여 가져오는 경우에는 Mapper를 거칠 필요가 없습니다.

**[예시: Mapper를 사용하지 않는 올바른 DB 직접 조회]**
```java
@Query("SELECT new com.ticketrush...SeatLayoutResponse(s.id, sl.id, sl.rowNo, sl.colNo) " +
       "FROM Seat s JOIN SeatLayout sl ON s.seatLayoutId = sl.id " +
       "WHERE s.performanceId = :performanceId")
List<SeatLayoutResponse> findSeatLayoutsByPerformanceId(@Param("performanceId") Long performanceId);
```
위와 같이 DB에서 이미 DTO 형태로 반환된다면, UseCase 계층에서 Mapper를 호출할 필요 없이 바로 반환하면 됩니다. 불필요한 엔티티 조회를 막아 성능상 훨씬 유리합니다.

### ⭕ Mapper가 필요한 경우
* 엔티티를 조회하여 비즈니스 로직을 수행한 뒤 응답 DTO로 변환할 때
* 외부 API 호출 결과를 내부 도메인 객체나 DTO로 변환할 때
* 클라이언트의 요청 DTO를 DB 저장을 위한 엔티티로 변환할 때

---

## 5. 📚 [부록] MapStruct 실무 활용 팁

### 5.1. `@Mapping` 주요 파라미터(속성) 가이드
MapStruct 매핑을 세밀하게 제어하기 위해 제공되는 주요 속성들입니다.

| 속성명 | 역할 및 설명 | 사용 예시 |
| :--- | :--- | :--- |
| **`target`** | **(필수)** 값을 세팅할 **결과물(Target) 객체의 필드명**을 지정합니다. | `@Mapping(target = "seatId", source = "id")` |
| **`source`** | 값을 뽑아올 **원본(Source) 객체의 필드명**을 지정합니다. 객체명과 함께 점(`.`)으로 탐색할 수 있습니다. | `@Mapping(source = "seat.id", target = "seatId")` |
| **`constant`** | 원본 데이터와 무관하게 **항상 고정된 문자열 값**을 대상 필드에 넣을 때 사용합니다. | `@Mapping(target = "status", constant = "AVAILABLE")` |
| **`defaultValue`** | 원본 필드의 값이 **`null`일 경우에만** 들어갈 기본(대체) 값을 지정합니다. | `@Mapping(source = "memo", target = "memo", defaultValue = "내용 없음")` |
| **`ignore`** | 특정 필드를 매핑 대상에서 아예 제외합니다. (DB 자동 생성 ID 보호 등에 활용) | `@Mapping(target = "id", ignore = true)` |
| **`expression`** | 특정 Java 메서드나 로직을 실행한 결과값을 직접 넣고 싶을 때 사용합니다. | `@Mapping(target = "createdAt", expression = "java(java.time.LocalDateTime.now())")` |

**[💡실무 복합 사용 예시]**

```java
@Mapping(target = "id", ignore = true)                                   // 1. id는 건드리지 마 (무시)
@Mapping(source = "seat.id", target = "seatId")                          // 2. seat의 id를 seatId로 매핑
@Mapping(source = "request.price", target = "price", defaultValue = "0") // 3. 가격이 null이면 0원으로 처리
@Mapping(target = "status", constant = "ACTIVE")                         // 4. 상태는 무조건 ACTIVE로 고정
@Mapping(target = "updatedAt", expression = "java(java.time.LocalDateTime.now())") // 5. 수정 시간은 현재 시간으로 계산
Seat toEntity(SeatUpdateRequest request, Seat seat);
```

### 5.2. `@MappingTarget`을 활용한 엔티티 업데이트 (JPA 더티 체킹)
`@MappingTarget`은 새로운 객체를 만들지 않고, **이미 존재하는 객체의 값을 업데이트(덮어쓰기)** 할 때 사용하는 강력한 기능입니다.
JPA 환경에서 기존 엔티티를 조회한 뒤 DTO의 값으로 수정할 때 이 기능을 사용하면, **JPA의 더티 체킹(Dirty Checking)이 자연스럽게 동작하여 자동으로 UPDATE 쿼리가 발생**합니다.

**[Mapper 인터페이스 정의]**
```java
@Mapper(componentModel = "spring")
public interface SeatMapper {

    // 반환 타입 없이 @MappingTarget을 사용하여 기존 객체(seat)의 값을 변경합니다.
    @Mapping(target = "id", ignore = true) // 식별자는 덮어쓰면 안 되므로 무시
    @Mapping(target = "performanceId", ignore = true)
    void updateEntityFromDto(SeatUpdateRequest request, @MappingTarget Seat seat);
}
```

**[UseCase에서의 실제 활용 예시]**
```java
@Service
@RequiredArgsConstructor
@Transactional // 더티 체킹을 위해 트랜잭션 필수
public class SeatUpdateUseCase {

    private final SeatRepository seatRepository;
    private final SeatMapper seatMapper;

    public void updateSeat(Long seatId, SeatUpdateRequest request) {
        // 1. DB에서 기존 엔티티 조회 (영속 상태)
        Seat seat = seatRepository.findById(seatId)
                .orElseThrow(() -> new IllegalArgumentException("좌석이 없습니다."));

        // 2. Mapper를 통해 DTO의 수정할 값들을 기존 엔티티에 덮어씌움
        // (이 메서드가 실행되고 나면 seat 객체의 내부 값이 request에 맞게 변경됨)
        seatMapper.updateEntityFromDto(request, seat);

        // 3. save() 호출 불필요! 트랜잭션 종료 시 더티 체킹으로 자동 UPDATE 실행
    }
}
```