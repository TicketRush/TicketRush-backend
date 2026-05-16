package com.ticketrush.boundedcontext.seat.app.support;

import com.ticketrush.boundedcontext.seat.app.dto.response.SeatStatusChangedResponse;

public interface SeatStatusEventSender {

  void send(SeatStatusChangedResponse event);
}
