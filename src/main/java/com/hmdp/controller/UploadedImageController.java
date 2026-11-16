package com.hmdp.controller;

import com.hmdp.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Positive;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

/**
 * Serves immutable validated images from the dedicated upload directory.
 * @author NeighborLife
 */
@RestController
@Validated
public class UploadedImageController {
    private static final String IMAGE_MISSING_CODE = "IMAGE_NOT_FOUND";
    private static final String IMAGE_MISSING_MESSAGE = "图片不存在";
    @Value("${app.upload.directory:./uploads}")
    private String directory;

    @GetMapping("/blogs/{owner}/{filename}")
    public ResponseEntity<Resource> image(@PathVariable("owner") @Positive Long owner,
            @PathVariable("filename") @Pattern(regexp = "[a-f0-9-]{36}\\.(png|jpeg|gif)") String filename) throws IOException {
        Path root = Paths.get(directory).toAbsolutePath().normalize();
        Path file = root.resolve("blogs").resolve(owner.toString()).resolve(filename).normalize();
        boolean readable = Files.isRegularFile(file) && file.toRealPath().startsWith(root.toRealPath());
        if (!readable) { throw new BusinessException(IMAGE_MISSING_CODE, IMAGE_MISSING_MESSAGE, HttpStatus.NOT_FOUND); }
        MediaType type = filename.endsWith(".png") ? MediaType.IMAGE_PNG
                : filename.endsWith(".gif") ? MediaType.IMAGE_GIF : MediaType.IMAGE_JPEG;
        return ResponseEntity.ok().contentType(type).contentLength(Files.size(file)).header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.maxAge(1L, TimeUnit.DAYS).cachePublic()).body(new FileSystemResource(file));
    }
}
