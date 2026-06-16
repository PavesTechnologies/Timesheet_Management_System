package com.intranet.dto.rms;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RMSResourcePageResponseDTO {
    private List<ResourceSummaryDTO> content;
    private long totalElements;
    private int totalPages;
    private int page;
    private int size;
}
