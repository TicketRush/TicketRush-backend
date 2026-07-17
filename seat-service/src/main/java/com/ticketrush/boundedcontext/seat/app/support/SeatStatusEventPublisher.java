package com.ticketrush.boundedcontext.seat.app.support;

import com.ticketrush.boundedcontext.seat.app.dto.response.SeatStatusChangedResponse;
import com.ticketrush.boundedcontext.seat.app.mapper.SeatMapper;
import com.ticketrush.boundedcontext.seat.domain.entity.Seat;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
@RequiredArgsConstructor
public class SeatStatusEventPublisher {

  private final SeatStatusEventSender seatStatusEventSender;
  private final SeatMapper seatMapper;

  public void publishAfterCommit(Seat seat) {
    SeatStatusChangedResponse event = seatMapper.toChangedResponse(seat);

    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      seatStatusEventSender.send(event);
      return;
    }

    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            seatStatusEventSender.send(event);
          }
        });
  }
}
