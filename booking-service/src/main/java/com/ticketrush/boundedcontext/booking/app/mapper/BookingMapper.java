package com.ticketrush.boundedcontext.booking.app.mapper;

import com.ticketrush.boundedcontext.booking.app.dto.request.BookingCreateRequest;
import com.ticketrush.boundedcontext.booking.domain.entity.Booking;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper(componentModel = "spring")
public interface BookingMapper {

  @Mapping(target = "bookingStatus", constant = "PENDING")
  Booking toEntity(BookingCreateRequest request);
}
