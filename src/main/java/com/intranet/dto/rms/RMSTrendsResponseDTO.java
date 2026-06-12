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
public class RMSTrendsResponseDTO {
    private List<PortfolioTrendDTO> daily;
    private List<PortfolioTrendDTO> weekly;
    private List<PortfolioTrendDTO> monthly;
}
