package com.hmdp.controller;

import com.hmdp.annotation.AdminOnly;
import com.hmdp.dto.CommentCreateDTO;
import com.hmdp.dto.Result;
import com.hmdp.service.IBlogCommentsService;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import javax.annotation.Resource;
import javax.validation.Valid;
import javax.validation.constraints.*;

/**
 * Comment publication, replies, likes and moderation endpoints.
 * @author NeighborLife
 */
@RestController
@RequestMapping("/blog-comments")
@Validated
public class BlogCommentsController {
    @Resource
    private IBlogCommentsService comments;
    @PostMapping
    public Result publish(@Valid @RequestBody CommentCreateDTO request) { return comments.publish(request); }
    @GetMapping("/of/blog/{id}")
    public Result list(@PathVariable("id") @Positive Long id,
            @RequestParam(value = "beforeId", defaultValue = "9223372036854775807") @Positive Long beforeId) {
        return comments.listComments(id, beforeId);
    }
    @DeleteMapping("/{id}")
    public Result delete(@PathVariable("id") @Positive Long id) { return comments.removeComment(id, false); }
    @AdminOnly
    @PostMapping("/{id}/hide")
    public Result hide(@PathVariable("id") @Positive Long id) { return comments.removeComment(id, true); }
    @PutMapping("/{id}/like")
    public Result like(@PathVariable("id") @Positive Long id, @RequestParam("liked") Boolean liked) { return comments.like(id, liked); }
    @PostMapping("/{id}/report")
    public Result report(@PathVariable("id") @Positive Long id,
            @RequestParam("reason") @NotBlank @Size(max = 128) String reason) { return comments.report(id, reason); }
}
