package com.agentscopea2a.v2.common;
import java.util.List;
public record PageResponse<T>(List<T> items, int page, int pageSize, int total) {}
