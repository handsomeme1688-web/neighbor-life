package com.hmdp.dto;

import lombok.Data;
import javax.validation.constraints.*;

/**
 * Author-independent fields accepted when publishing a blog.
 * @author NeighborLife
 */
@Data
public class BlogCreateDTO {
    @NotNull @Positive
    private Long shopId;
    @NotBlank @Size(max = 255)
    private String title;
    @NotBlank @Size(max = 2048)
    private String images;
    @NotBlank @Size(max = 4096)
    private String content;
}
