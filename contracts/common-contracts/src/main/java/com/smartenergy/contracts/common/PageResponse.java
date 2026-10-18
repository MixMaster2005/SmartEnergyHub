package com.smartenergy.contracts.common;

import java.util.List;

/** Reponse paginee standard (page 0-indexee). */
public record PageResponse<T>(List<T> items, int page, int size, long totalElements) {}
