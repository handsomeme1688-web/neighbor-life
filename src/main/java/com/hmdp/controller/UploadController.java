package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.Locale;
import java.util.UUID;

/**
 * Stores validated images beneath the authenticated user's own directory.
 *
 * @author NeighborLife
 * @date 2026/11/16
 */
@RestController
@RequestMapping("/upload")
public class UploadController {

    private final java.util.concurrent.Semaphore validationSlots = new java.util.concurrent.Semaphore(2);
    private static final long MAX_IMAGE_BYTES = 2L * 1024 * 1024;
    private static final long MAX_IMAGE_PIXELS = 10_000_000L;

    @Value("${app.upload.directory:./uploads}")
    private String uploadDirectory;

    @PostMapping("/blog")
    @com.hmdp.annotation.SlidingWindowRateLimit(key = "images:upload", windowSeconds = 60,
            globalLimit = 100, ipLimit = 10, userLimit = 5)
    public Result uploadImage(@RequestParam("file") MultipartFile image) throws IOException {
        if (image.isEmpty() || image.getSize() > MAX_IMAGE_BYTES) {
            return Result.fail("图片大小应在1字节至2MB之间");
        }
        if (!validationSlots.tryAcquire()) {
            throw new com.hmdp.exception.RateLimitExceededException("图片处理繁忙，请稍后重试");
        }
        String format;
        try { format = detectFormat(image); }
        finally { validationSlots.release(); }
        if (format == null) {
            return Result.fail("请选择有效的PNG、JPEG或GIF图片，像素总数上限为1000万");
        }
        String filename = "/blogs/" + UserHolder.getUser().getId() + "/"
                + UUID.randomUUID() + "." + format;
        Path target = resolveOwnedPath(filename);
        Files.createDirectories(target.getParent());
        image.transferTo(target);
        return Result.ok(filename);
    }

    @DeleteMapping("/blog")
    public Result deleteBlogImg(@RequestParam("name") String filename) throws IOException {
        Path target = resolveOwnedPath(filename);
        Files.deleteIfExists(target);
        return Result.ok();
    }

    private Path resolveOwnedPath(String filename) {
        String prefix = "/blogs/" + UserHolder.getUser().getId() + "/";
        if (!filename.startsWith(prefix)
                || !filename.substring(prefix.length()).matches("[a-f0-9-]{36}\\.(png|jpeg|gif)")) {
            throw new IllegalArgumentException("图片名称无效或访问权限不足");
        }
        Path root = Paths.get(uploadDirectory).toAbsolutePath().normalize();
        Path target = root.resolve(filename.substring(1)).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("图片路径无效");
        }
        return target;
    }

    private String detectFormat(MultipartFile image) throws IOException {
        try (InputStream content = image.getInputStream();
                ImageInputStream input = ImageIO.createImageInputStream(content)) {
            if (input == null) {
                return null;
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return null;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input);
                String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                boolean supported = "png".equals(format) || "jpeg".equals(format) || "gif".equals(format);
                if (pixels > MAX_IMAGE_PIXELS || !supported) {
                    return null;
                }
                return reader.read(0) == null ? null : format;
            } finally {
                reader.dispose();
            }
        }
    }
}
