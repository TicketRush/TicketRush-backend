package com.ticketrush.global.json;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class JsonConverter {

  private final ObjectMapper objectMapper;

  public String serialize(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception e) {
      String typeName = (value != null) ? value.getClass().getName() : "null";
      log.error("Failed to serialize value of type: {}", typeName, e);
      throw new SerializationException(e);
    }
  }

  public <T> T deserialize(String payload, Class<T> type) {
    try {
      return objectMapper.readValue(payload, type);
    } catch (Exception e) {
      int payloadLength = (payload != null) ? payload.length() : 0;
      log.error(
          "Failed to deserialize payload. Expected Type: {}, Payload Length: {}",
          type.getName(),
          payloadLength,
          e);
      throw new DeserializationException(e);
    }
  }

  public <T> T deserialize(String payload, TypeReference<T> type) {
    try {
      return objectMapper.readValue(payload, type);
    } catch (Exception e) {
      int payloadLength = (payload != null) ? payload.length() : 0;
      log.error(
          "Failed to deserialize payload. Expected Type Reference: {}, Payload Length: {}",
          type.getType().getTypeName(),
          payloadLength,
          e);
      throw new DeserializationException(e);
    }
  }

  public String serializeForLog(Object value, int maxLength) {
    if (value == null) {
      return "none";
    }
    try {
      String serialized = objectMapper.writeValueAsString(value);
      if (serialized.length() > maxLength) {
        return serialized.substring(0, maxLength) + "...(truncated)";
      }
      return serialized;
    } catch (Exception e) {
      String typeName = value.getClass().getName();
      String exceptionName = e.getClass().getSimpleName();
      log.debug(
          "Failed to serialize value for log. type={}, exception={}", typeName, exceptionName);
      return "un-serializable object(type=" + typeName + ", exception=" + exceptionName + ")";
    }
  }
}
