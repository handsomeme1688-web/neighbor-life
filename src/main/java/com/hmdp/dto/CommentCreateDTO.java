package com.hmdp.dto;

import lombok.Data;
import javax.validation.constraints.*;

/**
 * Validated comment content and an optional direct-reply target.
 * @author NeighborLife
 */
@Data
public class CommentCreateDTO {
    @NotNull @Positive
    private Long blogId;
    @Positive
    private Long answerId;
    @NotBlank @Size(max = 255)
    private String content;
}
