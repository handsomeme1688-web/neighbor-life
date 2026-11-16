package com.hmdp.dto;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * Cached coupon metadata; live inventory remains on the admission path.
 * @author NeighborLife
 */
@Data
public class VoucherDetailDTO {
    private Long id;
    private Long shopId;
    private String title;
    private String subTitle;
    private String rules;
    private Long payValue;
    private Long actualValue;
    private Integer type;
    private Integer status;
    private LocalDateTime beginTime;
    private LocalDateTime endTime;
}
