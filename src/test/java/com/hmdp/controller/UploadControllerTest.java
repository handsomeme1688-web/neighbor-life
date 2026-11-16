package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies content validation and image ownership on disk.
 * @author NeighborLife
 */
class UploadControllerTest {
    @TempDir
    Path directory;
    private UploadController controller;

    @BeforeEach
    void setUp() {
        controller = new UploadController();
        ReflectionTestUtils.setField(controller, "uploadDirectory", directory.toString());
        UserDTO user = new UserDTO();
        user.setId(1L);
        UserHolder.saveUser(user);
    }

    @AfterEach
    void cleanUp() {
        UserHolder.removeUser();
    }

    @Test
    void storesValidatedImageAndDeletesOwnedFile() throws Exception {
        ByteArrayOutputStream imageBytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", imageBytes);
        Result uploaded = controller.uploadImage(new MockMultipartFile("file", "untrusted.txt",
                "text/plain", imageBytes.toByteArray()));
        assertTrue(uploaded.getSuccess());
        String name = uploaded.getData().toString();
        assertTrue(name.startsWith("/blogs/1/"));
        Path image = directory.resolve(name.substring(1));
        assertTrue(Files.exists(image));
        assertTrue(controller.deleteBlogImg(name).getSuccess());
        assertFalse(Files.exists(image));
    }

    @Test
    void rejectsFileDisguisedAsImage() throws Exception {
        Result result = controller.uploadImage(new MockMultipartFile("file", "fake.png", "image/png",
                new byte[]{1, 2, 3}));
        assertFalse(result.getSuccess());
    }

    @Test
    void rejectsTraversal() {
        assertThrows(IllegalArgumentException.class, () -> controller.deleteBlogImg("/blogs/1/../../other"));
    }

    @Test
    void rejectsAnotherUsersImage() {
        assertThrows(IllegalArgumentException.class,
                () -> controller.deleteBlogImg("/blogs/2/aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa.png"));
    }
}
