package com.hmdp.dto;

import lombok.Data;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Pattern;

/**
 * Buyer-presented short-lived redemption credential.
 * @author NeighborLife
 */
@Data
public class RedemptionDTO {
    @NotBlank
    @Pattern(regexp = "[A-Za-z0-9_-]{43}")
    private String code;
}
