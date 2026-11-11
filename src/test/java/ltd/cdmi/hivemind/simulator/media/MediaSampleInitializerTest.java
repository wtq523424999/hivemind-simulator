// Copyright (C) 2026 CDMI
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as
// published by the Free Software Foundation, either version 3 of the
// License, or (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
// GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program. If not, see <https://www.gnu.org/licenses/>.

package ltd.cdmi.hivemind.simulator.media;

import ltd.cdmi.hivemind.simulator.config.LiveConfigStore;
import ltd.cdmi.hivemind.simulator.config.MqttProperties;
import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import ltd.cdmi.hivemind.simulator.config.SimulatorProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MediaSampleInitializer 单元测试。
 * <p>覆盖 TDD-SPEC.md 2.20 媒体管理 TC-MEDIA-016/017（media-dir 预置示例照片/视频）：
 * 任务完成（落地）即异步触发媒体上传，无运行时补传机会，空目录会导致落地后无真实文件可上传。
 */
class MediaSampleInitializerTest {

    @TempDir
    Path tempDir;

    private MediaSampleInitializer createInitializer(String mediaDir) {
        SimulatorProperties props = new SimulatorProperties(
                new SimulatorProperties.Location(30.67, 104.07, 500.0),
                new SimulatorProperties.Log(2000),
                new SimulatorProperties.Live(false, "", "", null),
                new SimulatorProperties.Media("", false, 0, false, 0),
                null, null, null, null, null
        );
        RuntimeConfig runtimeConfig = new RuntimeConfig(
                new MqttProperties("127.0.0.1", 1883, "user", "pass", "sim-", "mon-"),
                props, new LiveConfigStore());
        runtimeConfig.setMediaDir(mediaDir);
        return new MediaSampleInitializer(runtimeConfig, new MediaUploader());
    }

    // ==================== TC-MEDIA-016：空目录自动预置 ====================

    @Test
    @DisplayName("TC-MEDIA-016: media-dir 不存在时自动创建并预置示例照片+视频")
    void presetsSamplesIntoMissingDirectory() {
        Path dir = tempDir.resolve("media");
        MediaSampleInitializer initializer = createInitializer(dir.toString());

        initializer.ensurePresetMediaFiles();

        assertTrue(Files.exists(dir.resolve("sample-photo.jpg")), "示例照片应被预置");
        assertTrue(Files.exists(dir.resolve("sample-photo-2.jpg")), "第二张示例照片应被预置");
        assertTrue(Files.exists(dir.resolve("sample-video.mp4")), "示例视频应被预置");
        // 预置文件可被媒体上传扫描到（扩展名在支持列表内）
        List<Path> files = new MediaUploader().listMediaFiles(dir.toString());
        assertEquals(4, files.size(), "listMediaFiles 应扫描到 3 张示例照片 + 1 个示例视频");
        // 拍照只用图片：视频不进入照片池
        List<Path> photos = new MediaUploader().listPhotoFiles(dir.toString());
        assertEquals(3, photos.size(), "listPhotoFiles 只应扫描到示例照片");
        assertTrue(photos.stream().noneMatch(p -> p.getFileName().toString().endsWith(".mp4")),
                "视频不应进入照片素材池");
    }

    @Test
    @DisplayName("TC-MEDIA-016: 目录存在但为空时同样预置，且预置体积小（<200KB）")
    void presetsIntoEmptyDirectoryWithSmallSize() {
        Path dir = tempDir;
        MediaSampleInitializer initializer = createInitializer(dir.toString());

        initializer.ensurePresetMediaFiles();

        try {
            long photoSize = Files.size(dir.resolve("sample-photo.jpg"));
            long videoSize = Files.size(dir.resolve("sample-video.mp4"));
            assertTrue(photoSize > 0 && photoSize < 200 * 1024, "照片体积应在 (0, 200KB)，实际 " + photoSize);
            assertTrue(videoSize > 0 && videoSize < 200 * 1024, "视频体积应在 (0, 200KB)，实际 " + videoSize);
        } catch (Exception e) {
            fail(e);
        }
    }

    @Test
    @DisplayName("TC-MEDIA-016: 重复调用幂等，不重复写入不报错")
    void idempotentOnRepeatedCalls() {
        Path dir = tempDir.resolve("media");
        MediaSampleInitializer initializer = createInitializer(dir.toString());

        initializer.ensurePresetMediaFiles();
        initializer.ensurePresetMediaFiles();

        List<Path> files = new MediaUploader().listMediaFiles(dir.toString());
        assertEquals(4, files.size(), "重复调用不应产生额外文件");
    }

    // ==================== TC-MEDIA-017：不污染用户文件/未配置不预置 ====================

    @Test
    @DisplayName("TC-MEDIA-017: 目录已有用户媒体文件时不预置（不污染用户文件集）")
    void skipsWhenUserFilesPresent() {
        Path dir = tempDir.resolve("media");
        try {
            Files.createDirectories(dir);
            Files.write(dir.resolve("myphoto.jpg"), new byte[]{1, 2, 3});
        } catch (Exception e) {
            fail(e);
        }
        MediaSampleInitializer initializer = createInitializer(dir.toString());

        initializer.ensurePresetMediaFiles();

        assertFalse(Files.exists(dir.resolve("sample-photo.jpg")), "不应预置示例照片");
        assertFalse(Files.exists(dir.resolve("sample-photo-2.jpg")), "不应预置示例照片");
        assertFalse(Files.exists(dir.resolve("sample-video.mp4")), "不应预置示例视频");
        try (var files = Files.list(dir)) {
            assertEquals(1, files.count(), "目录应只含用户自己的文件");
        } catch (Exception e) {
            fail(e);
        }
    }

    @Test
    @DisplayName("TC-MEDIA-018: 旧版纯色占位图在同名文件被识别后替换为真实示例照片")
    void replacesLegacyPlaceholderOnUpgrade() throws Exception {
        Path dir = tempDir.resolve("media");
        Files.createDirectories(dir);
        // 老部署状态：只有旧版蓝色占位图 + 示例视频，目录非空
        Files.write(dir.resolve("sample-photo.jpg"), legacyPlaceholderBytes());
        Files.write(dir.resolve("sample-video.mp4"), new byte[]{1, 2, 3});

        createInitializer(dir.toString()).ensurePresetMediaFiles();

        byte[] replaced = Files.readAllBytes(dir.resolve("sample-photo.jpg"));
        assertFalse(java.util.Arrays.equals(legacyPlaceholderBytes(), replaced),
                "旧占位图应被真实示例照片替换");
        assertTrue(replaced.length > 50 * 1024, "替换后应是真实照片而不是几百字节的单色图");
        assertTrue(Files.exists(dir.resolve("sample-photo-2.jpg")), "应补上新增的示例照片");
        // 用户/示例视频不被触碰
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(dir.resolve("sample-video.mp4")));
    }

    @Test
    @DisplayName("TC-MEDIA-018: 用户自己的 sample-photo.jpg（内容不同）不会被覆盖")
    void keepsUserFileWithSamePresetName() throws Exception {
        Path dir = tempDir.resolve("media");
        Files.createDirectories(dir);
        byte[] userContent = "user-owned-photo".getBytes();
        Files.write(dir.resolve("sample-photo.jpg"), userContent);

        createInitializer(dir.toString()).ensurePresetMediaFiles();

        assertArrayEquals(userContent, Files.readAllBytes(dir.resolve("sample-photo.jpg")),
                "内容与旧占位图不同的同名文件必须原样保留");
    }

    /** 旧版纯色占位图的字节副本（取自升级前 git 历史的 sample-photo.jpg）。 */
    private static byte[] legacyPlaceholderBytes() throws Exception {
        try (var in = MediaSampleInitializerTest.class.getResourceAsStream(
                "/media-samples/legacy-blue-placeholder.jpg")) {
            assertNotNull(in, "测试资源缺失: legacy-blue-placeholder.jpg");
            return in.readAllBytes();
        }
    }

    @Test
    @DisplayName("TC-MEDIA-017: media-dir 未配置时不预置、不创建目录、不报错")
    void noOpWhenMediaDirBlank() {
        Path dir = tempDir.resolve("should-not-exist");
        MediaSampleInitializer initializer = createInitializer("");

        initializer.ensurePresetMediaFiles();

        assertFalse(Files.exists(dir), "未配置时不应创建目录");
        assertFalse(Files.exists(tempDir.resolve("sample-photo.jpg")), "未配置时不应解出文件");
    }

    @Test
    @DisplayName("init()（应用启动钩子）与 ensurePresetMediaFiles 行为一致")
    void initBehavesSameAsEnsure() {
        Path dir = tempDir.resolve("media");
        MediaSampleInitializer initializer = createInitializer(dir.toString());

        initializer.init();

        assertTrue(Files.exists(dir.resolve("sample-photo.jpg")));
        assertTrue(Files.exists(dir.resolve("sample-video.mp4")));
    }

    // ==================== 用户照片优先于预置样例 ====================

    @Test
    @DisplayName("TC-MEDIA-021: 目录有用户照片时拍照只取用户照片，预置样例不参与轮换")
    void userPhotosTakePriorityOverPresets() {
        Path dir = tempDir.resolve("media");
        MediaSampleInitializer initializer = createInitializer(dir.toString());
        initializer.ensurePresetMediaFiles(); // 先预置（模拟 Docker 启动）
        try {
            // 故意取一个按文件名排序在预置样例之后的名称：
            // 若实现只是按文件名排序取第一个，这张用户照片永远不会被选中
            Files.write(dir.resolve("zzz-user-photo.jpg"), new byte[]{1, 2, 3});
        } catch (Exception e) {
            fail(e);
        }

        MediaUploader uploader = new MediaUploader();
        List<Path> userPhotos = uploader.listUserPhotoFiles(dir.toString());

        assertEquals(1, userPhotos.size(), "只有用户照片应被选中");
        assertEquals("zzz-user-photo.jpg", userPhotos.get(0).getFileName().toString());
    }

    @Test
    @DisplayName("TC-MEDIA-021: 目录只有预置样例时回退到样例照片，示例视频仍不参与")
    void fallsBackToPresetsWhenNoUserPhotos() {
        Path dir = tempDir.resolve("media");
        createInitializer(dir.toString()).ensurePresetMediaFiles();

        MediaUploader uploader = new MediaUploader();
        assertTrue(uploader.listUserPhotoFiles(dir.toString()).isEmpty(), "没有用户照片");
        List<Path> fallback = uploader.listPhotoFiles(dir.toString());
        assertEquals(3, fallback.size(), "回退到 3 张预置示例照片");
    }

    @Test
    @DisplayName("TC-MEDIA-021: 内置示例照片有多张，连续拍照轮换不同图片")
    void samplePhotoRotatesAcrossPresets() throws Exception {
        MediaUploader uploader = new MediaUploader();
        byte[] first = null;
        java.util.Set<Integer> sizes = new java.util.HashSet<>();
        for (int i = 0; i < 3; i++) {
            Path photo = uploader.samplePhoto(i);
            try {
                byte[] bytes = Files.readAllBytes(photo);
                assertTrue(bytes.length > 0, "示例照片必须有内容");
                sizes.add(bytes.length);
                if (first == null) {
                    first = bytes;
                } else {
                    assertFalse(java.util.Arrays.equals(first, bytes),
                            "第 " + (i + 1) + " 张示例照片应与第 1 张不同（不应反复上传同一张工位图）");
                }
            } finally {
                Files.deleteIfExists(photo);
            }
        }
        assertEquals(3, sizes.size(), "内置示例照片应为 3 张不同图片");
    }
}
