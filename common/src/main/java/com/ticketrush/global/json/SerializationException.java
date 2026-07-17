package com.ticketrush.global.json;

import com.ticketrush.global.exception.BusinessException;
import com.ticketrush.global.status.ErrorStatus;

public class SerializationException extends BusinessException {
  public SerializationException(Throwable cause) {
    super(ErrorStatus.JSON_PROCESSING_ERROR, cause);
  }
}
