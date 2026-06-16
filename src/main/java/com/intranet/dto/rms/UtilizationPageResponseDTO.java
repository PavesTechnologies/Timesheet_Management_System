package com.intranet.dto.rms;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Generic paginated response wrapper for all utilization dimension endpoints.
 * Used for resources, projects, clients, and roles.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UtilizationPageResponseDTO<T> {
    private List<T> content;
    private long totalElements;
    private int totalPages;
    private int page;
    private int size;
}
