package com.hmdp.dto;

import lombok.Data;
import javax.validation.constraints.*;
import java.time.LocalDateTime;

/**
 * Whitelisted merchant metadata for shop creation.
 * @author NeighborLife
 */
@Data
public class ShopCreateDTO {
    @NotBlank @Size(max = 128)
    private String name;
    @NotNull @Positive
    private Long typeId;
    @NotBlank @Size(max = 1024)
    private String images;
    @Size(max = 128)
    private String area;
    @NotBlank @Size(max = 255)
    private String address;
    @NotNull @DecimalMin("-180") @DecimalMax("180")
    private Double x;
    @NotNull @DecimalMin("-90") @DecimalMax("90")
    private Double y;
    @PositiveOrZero
    private Long avgPrice;
    @Size(max = 64)
    private String openHours;
}
