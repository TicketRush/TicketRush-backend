package com.ticketrush.exception;

import com.ticketrush.dto.response.ApiResponse;
import com.ticketrush.status.ErrorStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebExceptionHandler;
import reactor.core.publisher.Mono;
import tools.jackson.databind.ObjectMapper;

@Component
@Order(-2)
@RequiredArgsConstructor
public class GatewayBusinessExceptionHandler implements WebExceptionHandler {

  private final ObjectMapper objectMapper;

  @Override
  public Mono<Void> handle(ServerWebExchange exchange, Throwable throwable) {
    if (!(throwable instanceof BusinessException exception)) {
      return Mono.error(throwable);
    }

    ServerHttpResponse response = exchange.getResponse();
    if (response.isCommitted()) {
      return Mono.error(throwable);
    }

    ErrorStatus status = exception.getErrorStatus();
    ApiResponse<Void> apiResponse =
        new ApiResponse<>(false, status.getCode(), status.getMessage(), null, null);

    byte[] body;
    try {
      body = objectMapper.writeValueAsBytes(apiResponse);
    } catch (RuntimeException serializationException) {
      return Mono.error(serializationException);
    }

    response.setStatusCode(status.getHttpStatus());
    response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
    return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
  }
}
