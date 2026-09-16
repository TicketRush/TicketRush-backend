package com.ticketrush.global.config;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.introspect.AnnotatedMember;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import io.swagger.v3.core.converter.AnnotatedType;
import io.swagger.v3.core.converter.ModelConverter;
import io.swagger.v3.core.converter.ModelConverterContext;
import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.oas.models.media.Schema;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 생성되는 OpenAPI 스키마의 프로퍼티 이름을 실제 HTTP 계약(snake_case)과 맞춘다 (#658).
 *
 * <p>springdoc 은 스키마 생성을 Jackson 2 기반 swagger-core 에 맡기는데, 그 매퍼는 swagger 쪽 것이라 {@link
 * JacksonConfig}(Jackson 3)의 SNAKE_CASE 를 볼 수 없다. 그래서 실제 요청·응답은 snake_case 인데 문서만 camelCase 로 나갔다.
 *
 * <p>매퍼에 네이밍 전략을 걸어 해결할 수는 없다. 실측으로 확인한 두 가지 때문이다.
 *
 * <ul>
 *   <li>원본 매퍼에 걸면 그 인스턴스가 OpenAPI 문서 자체의 직렬화에도 쓰여 {@code requestBody} 가 {@code request_body} 가 되는 등
 *       문서 구조까지 깨진다.
 *   <li>복제본에 걸면 문서 구조는 무사하지만 sealed 타입의 하위 타입 해석이 실패한다. {@code PaginationInfo} 의 {@code oneOf} 가
 *       가리키는 {@code PageInfo}·{@code CursorInfo} 가 통째로 사라져 깨진 참조만 남는다.
 * </ul>
 *
 * <p>그래서 매퍼는 기본 그대로 두고(= 하위 타입·파일·다형성 처리가 전부 원래대로 동작한다) 만들어진 스키마의 이름만 뒤에서 바꾼다.
 *
 * <p>변환 규칙은 Jackson 2·3 이 동일하다. 두 라이브러리의 {@code SNAKE_CASE} 구현이 글자 단위로 같아서, 문서가 말하는 이름과 런타임이 실제로 쓰는
 * 이름이 어긋나지 않는다.
 */
public class SnakeCaseModelResolver extends ModelResolver {

  private static final Logger log = LoggerFactory.getLogger(SnakeCaseModelResolver.class);

  private static final PropertyNamingStrategies.NamingBase SNAKE_CASE =
      PropertyNamingStrategies.SnakeCaseStrategy.INSTANCE;

  public SnakeCaseModelResolver(ObjectMapper mapper) {
    super(mapper);
  }

  @Override
  public Schema resolve(
      AnnotatedType type, ModelConverterContext context, Iterator<ModelConverter> chain) {
    return super.resolve(type, wrap(context), chain);
  }

  /**
   * 재진입 때 래퍼가 겹겹이 쌓이지 않게 한 겹만 유지한다.
   *
   * <p>실제로 겹치는 일은 거의 없다 — swagger-core 의 컨텍스트는 체인을 재시작할 때 자기 자신을 넘기므로 이 리졸버가 다시 불려도 원본을 받는다. 겹치더라도
   * 변환이 멱등이라 결과는 같다. 그러니 이 가드는 안전장치라기보다 할당을 줄이는 쪽에 가깝다.
   */
  private ModelConverterContext wrap(ModelConverterContext context) {
    return context instanceof RenamingContext ? context : new RenamingContext(context);
  }

  /**
   * 이름을 바꾸면 안 되는 프로퍼티를 가려낸다. 내성 검사가 실패하면 {@code null} 을 돌려준다.
   *
   * <p>{@code @JsonProperty} 로 이름을 직접 적은 것은 그 이름이 곧 실제 계약이다(예: {@code refreshToken} · {@code
   * socialId}). Jackson 은 이렇게 명시한 이름에 네이밍 전략을 적용하지 않으므로 런타임도 그 이름을 쓴다. {@code @Schema(name=...)} 은
   * 문서에 그 이름으로 내보내겠다는 뜻이라 마찬가지로 둔다 — multipart 파트명이 이 경로로 보호된다.
   *
   * <p>직렬화·역직렬화 양쪽을 다 보는 이유는 요청 전용 DTO 때문이다. getter 없이 필드나 setter 로만 바인딩되는 타입은 직렬화 관점에서 프로퍼티가 잡히지
   * 않아, 한쪽만 보면 실제 계약인 이름을 놓친다.
   */
  private Set<String> namesToKeep(Type type) {
    if (type == null) {
      return Set.of();
    }
    try {
      JavaType javaType = objectMapper().constructType(type);
      Set<String> keep = new HashSet<>();
      collectNamesToKeep(objectMapper().getSerializationConfig().introspect(javaType), keep);
      collectNamesToKeep(objectMapper().getDeserializationConfig().introspect(javaType), keep);
      return keep;
    } catch (RuntimeException e) {
      // 무엇을 지켜야 하는지 모르는 채로 바꾸면 실제 계약인 이름까지 바꿔 버린다. 그래서 아예 바꾸지 않는다.
      // 조용히 넘어가면 문서만 잘못 나가고 아무도 모르므로 흔적을 남긴다.
      log.warn("스키마 이름 검사에 실패해 이 타입의 이름을 그대로 둔다: {}", type, e);
      return null;
    }
  }

  private void collectNamesToKeep(BeanDescription description, Set<String> keep) {
    for (BeanPropertyDefinition property : description.findProperties()) {
      if (property.isExplicitlyNamed()) {
        keep.add(property.getName());
        continue;
      }
      AnnotatedMember member = property.getPrimaryMember();
      if (member == null) {
        continue;
      }
      addSchemaName(member.getAnnotation(io.swagger.v3.oas.annotations.media.Schema.class), keep);

      // 배열 파트는 @Schema 가 @ArraySchema 안에 들어가 있어 위 경로로는 읽히지 않는다.
      io.swagger.v3.oas.annotations.media.ArraySchema arraySchema =
          member.getAnnotation(io.swagger.v3.oas.annotations.media.ArraySchema.class);
      if (arraySchema != null) {
        addSchemaName(arraySchema.arraySchema(), keep);
      }
    }
  }

  private void addSchemaName(io.swagger.v3.oas.annotations.media.Schema schema, Set<String> keep) {
    if (schema != null && !schema.name().isEmpty()) {
      keep.add(schema.name());
    }
  }

  private void rename(Schema<?> model, Type type) {
    Set<String> keep = namesToKeep(type);
    if (keep == null) {
      return;
    }
    renameProperties(model, keep);
    renameRequired(model, keep);
  }

  private void renameProperties(Schema<?> model, Set<String> keep) {
    Map<String, Schema> properties = model.getProperties();
    if (properties == null || properties.isEmpty()) {
      return;
    }

    Map<String, Schema> renamed = new LinkedHashMap<>();
    for (Map.Entry<String, Schema> entry : properties.entrySet()) {
      String name = translate(entry.getKey(), keep);
      Schema previous = renamed.putIfAbsent(name, entry.getValue());
      if (previous != null) {
        // 덮어쓰면 프로퍼티 하나가 문서에서 흔적 없이 사라진다. 먼저 온 것을 남기고 사실을 남긴다.
        log.warn("스키마 프로퍼티 이름이 겹쳐 뒤엣것을 버린다: {} (원본 {})", name, entry.getKey());
      }
    }
    model.setProperties(renamed);
  }

  /**
   * {@code required} 도 프로퍼티 이름을 담고 있어 같이 바꾼다. 놓치면 문서상 없는 필드를 필수라고 말하게 된다.
   *
   * <p>프로퍼티 유무와 무관하게 수행한다 — swagger-core 는 합성 스키마를 만들 때 properties 를 걷어내고 required 만 남기는 경로가 있어서,
   * 둘을 묶어 두면 그 경로에서 이름이 어긋난다.
   */
  private void renameRequired(Schema<?> model, Set<String> keep) {
    List<String> required = model.getRequired();
    if (required == null || required.isEmpty()) {
      return;
    }
    List<String> renamed = new ArrayList<>(required.size());
    for (String name : required) {
      renamed.add(translate(name, keep));
    }
    model.setRequired(renamed);
  }

  private String translate(String name, Set<String> keep) {
    return keep.contains(name) ? name : SNAKE_CASE.translate(name);
  }

  /** {@code defineModel} 로 들어오는 스키마의 이름만 바꿔 넘기는 얇은 위임 컨텍스트. */
  private final class RenamingContext implements ModelConverterContext {

    private final ModelConverterContext delegate;

    private RenamingContext(ModelConverterContext delegate) {
      this.delegate = delegate;
    }

    /**
     * 여기서는 이름을 바꾸지 않는다. 이 오버로드로 들어오는 것은 이미 아래 4-arg 경로로 정의를 마친 스키마의 재등록뿐이라, 다시 훑으면 변환된 이름을 한 번 더 훑는
     * 셈이 된다.
     */
    @Override
    public void defineModel(String name, Schema model) {
      delegate.defineModel(name, model);
    }

    @Override
    public void defineModel(String name, Schema model, AnnotatedType type, String prevName) {
      rename(model, type == null ? null : type.getType());
      delegate.defineModel(name, model, type, prevName);
    }

    @Override
    public void defineModel(String name, Schema model, Type type, String prevName) {
      rename(model, type);
      delegate.defineModel(name, model, type, prevName);
    }

    @Override
    public Schema resolve(AnnotatedType type) {
      return delegate.resolve(type);
    }

    @Override
    public Map<String, Schema> getDefinedModels() {
      return delegate.getDefinedModels();
    }

    @Override
    public Iterator<ModelConverter> getConverters() {
      return delegate.getConverters();
    }
  }
}
