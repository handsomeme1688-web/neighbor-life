package com.hmdp.dto;

import lombok.Data;
import javax.validation.constraints.*;
import java.time.LocalDateTime;

/**
 * Validated coupon metadata and optional flash-sale schedule.
 * @author NeighborLife
 */
@Data
public class VoucherCreateDTO {
    @NotNull @Positive
    private Long shopId;
    @NotBlank @Size(max = 255)
    private String title;
    @Size(max = 255)
    private String subTitle;
    @Size(max = 1024)
    private String rules;
    @NotNull @Positive @Max(100000000)
    private Long payValue;
    @NotNull @Positive @Max(100000000)
    private Long actualValue;
    @Positive
    private Integer stock;
    private LocalDateTime beginTime;
    private LocalDateTime endTime;
}
