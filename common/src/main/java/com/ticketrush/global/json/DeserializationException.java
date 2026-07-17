package com.ticketrush.global.json;

import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;

public class DeserializationException extends BusinessException {
  public DeserializationException(Throwable cause) {
    super(ErrorStatus.JSON_PROCESSING_ERROR, cause);
  }
}
