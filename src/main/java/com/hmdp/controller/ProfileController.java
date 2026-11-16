package com.hmdp.controller;

import com.hmdp.dto.ProfileUpdateDTO;
import com.hmdp.dto.Result;
import com.hmdp.service.UserProfileService;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import javax.validation.Valid;

/**
 * Authenticated user profile and permission endpoints.
 * @author NeighborLife
 */
@RestController
@RequestMapping("/user")
public class ProfileController {
    @Resource
    private UserProfileService profiles;
    @GetMapping("/profile")
    public Result profile() { return profiles.profile(); }
    @PutMapping("/profile")
    public Result update(@Valid @RequestBody ProfileUpdateDTO request) { return profiles.update(request); }
    @GetMapping("/permissions")
    public Result permissions() { return profiles.permissions(); }
}
