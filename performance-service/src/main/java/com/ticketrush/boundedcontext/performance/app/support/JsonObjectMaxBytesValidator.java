package com.ticketrush.boundedcontext.performance.app.support;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.nio.charset.StandardCharsets;
import tools.jackson.databind.JsonNode;

public class JsonObjectMaxBytesValidator
    implements ConstraintValidator<JsonObjectMaxBytes, JsonNode> {

  private int max;
  private int maxDepth;

  @Override
  public void initialize(JsonObjectMaxBytes constraint) {
    this.max = constraint.max();
    this.maxDepth = constraint.maxDepth();
  }

  /**
   * JSON {@code null}은 Java null이 아니라 {@code NullNode}로 바인딩된다. "값 없음"으로 보고 통과시킨다 — 등록에서는 캐릭터 없음,
   * 수정에서는 수정 안 함이며 그 해석은 매퍼·도메인이 한다.
   */
  @Override
  public boolean isValid(JsonNode value, ConstraintValidatorContext context) {
    if (value == null || value.isNull()) {
      return true;
    }
    if (!value.isObject()) {
      return false;
    }
    if (value.toString().getBytes(StandardCharsets.UTF_8).length > max) {
      return false;
    }
    return depthOf(value) <= maxDepth;
  }

  /** 컨테이너(객체·배열)만 깊이로 센다. 값 노드는 0, {@code {}}는 1, {@code {"a":{}}}는 2. */
  private static int depthOf(JsonNode node) {
    if (!node.isContainer()) {
      return 0;
    }
    int childMax = 0;
    for (JsonNode child : node) {
      childMax = Math.max(childMax, depthOf(child));
    }
    return childMax + 1;
  }
}
