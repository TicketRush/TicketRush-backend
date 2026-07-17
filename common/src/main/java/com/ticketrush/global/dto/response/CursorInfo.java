package com.ticketrush.global.dto.response;

public record CursorInfo(boolean hasNext, Long nextCursor, int size) implements PaginationInfo {}
