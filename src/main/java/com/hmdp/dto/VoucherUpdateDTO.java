package com.hmdp.dto;

import lombok.Data;
import javax.validation.constraints.*;
import java.time.LocalDateTime;

/**
 * Whitelisted coupon metadata and publication state update.
 * @author NeighborLife
 */
@Data
public class VoucherUpdateDTO {
    @NotNull @Positive
    private Long id;
    @Size(max = 255)
    private String title;
    @Size(max = 255)
    private String subTitle;
    @Size(max = 1024)
    private String rules;
    @Positive @Max(100000000)
    private Long payValue;
    @Positive @Max(100000000)
    private Long actualValue;
    @Min(1) @Max(3)
    private Integer status;
}
