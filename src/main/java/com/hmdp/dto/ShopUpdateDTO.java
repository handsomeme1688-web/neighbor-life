package com.hmdp.dto;

import lombok.Data;
import javax.validation.constraints.*;
import java.time.LocalDateTime;

/**
 * Whitelisted partial shop update.
 * @author NeighborLife
 */
@Data
public class ShopUpdateDTO {
    @NotNull @Positive
    private Long id;
    @Size(max = 128)
    private String name;
    @Positive
    private Long typeId;
    @Size(max = 1024)
    private String images;
    @Size(max = 128)
    private String area;
    @Size(max = 255)
    private String address;
    @DecimalMin("-180") @DecimalMax("180")
    private Double x;
    @DecimalMin("-90") @DecimalMax("90")
    private Double y;
    @PositiveOrZero
    private Long avgPrice;
    @Size(max = 64)
    private String openHours;
}
