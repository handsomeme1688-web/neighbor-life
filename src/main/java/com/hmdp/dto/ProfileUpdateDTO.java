package com.hmdp.dto;

import lombok.Data;
import javax.validation.constraints.*;
import java.time.LocalDate;

/**
 * Fields an authenticated user may edit in their own profile.
 * @author NeighborLife
 */
@Data
public class ProfileUpdateDTO {
    @Size(min = 1, max = 32)
    private String nickName;
    @Size(max = 255)
    private String icon;
    @Size(max = 64)
    private String city;
    @Size(max = 128)
    private String introduce;
    @Min(0) @Max(1)
    private Integer gender;
    @Past
    private LocalDate birthday;
}
