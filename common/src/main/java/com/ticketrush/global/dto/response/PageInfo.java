package com.ticketrush.global.dto.response;

public record PageInfo(int pageIndex, int size, boolean hasNext, long totalElements, int totalPages)
    implements PaginationInfo {}
