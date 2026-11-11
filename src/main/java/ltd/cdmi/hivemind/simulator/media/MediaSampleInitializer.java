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

import ltd.cdmi.hivemind.simulator.config.RuntimeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 媒体示例文件预置器。
 * <p>设计背景（TDD-SPEC TC-MEDIA-016~018）：航线任务完成（落地）即异步触发媒体上传，
 * 无运行时补传机会；Docker 新部署 media-dir 为空目录会导致落地后无真实文件可上传
 * （仅虚构元数据）。因此在 media-dir 已配置且无媒体文件时，从 JAR 内置资源解出
 * 一个小体积示例视频与若干张真实示例照片（合计约 300KB，S3 上传秒级完成），
 * 保证 STS→S3→callback 全链路开箱可验证；照片用于航点拍照，多张可轮换。
 * <p>触发时机：应用启动（media-dir 已有持久化配置时）、前端保存直播/媒体配置后
 * （{@code POST /api/live/config}，目录首次配置场景）。
 * <p>预置不污染用户文件：目录中已有受支持的媒体文件时不做任何写入；
 * media-dir 未配置时保持既有降级行为（TC-MEDIA-012 仅元数据上报）。
 * <p>用户照片优先：一旦目录里出现用户自己放入/上传的照片，拍照上传就不再取预置样例
 * （见 {@link MediaUploader#listUserPhotoFiles}）。
 */
@Component
public class MediaSampleInitializer {

    private static final Logger log = LoggerFactory.getLogger(MediaSampleInitializer.class);

    /** 旧版本预置的纯色占位图文件名 */
    private static final String LEGACY_PLACEHOLDER_NAME = "sample-photo.jpg";
    /** 旧版纯色占位图（1280x720 全蓝）的 SHA-256，用于识别老部署而不必随包携带该图 */
    private static final String LEGACY_PLACEHOLDER_SHA256 =
            "2a2b102dc79ee1649e416504ca4eb47db9f93b0977e98823c440e8c05a0b8139";

    private final RuntimeConfig runtimeConfig;
    private final MediaUploader mediaUploader;

    public MediaSampleInitializer(RuntimeConfig runtimeConfig, MediaUploader mediaUploader) {
        this.runtimeConfig = runtimeConfig;
        this.mediaUploader = mediaUploader;
    }

    /**
     * 应用启动时检查并预置（media-dir 已配置的场景）。
     * <p>预置失败仅记录 warn，不影响应用启动。
     */
    @jakarta.annotation.PostConstruct
    public void init() {
        ensurePresetMediaFiles();
    }

    /**
     * 确保 media-dir 中有可上传的媒体文件：目录已配置且无任何受支持媒体文件时，
     * 从 classpath 解出内置示例照片+视频；已有用户文件时不做任何写入。
     * <p>旧版本预置的纯色占位图会被替换（见 {@link #replaceLegacyPlaceholder}），
     * 否则升级后老部署仍然只有一张蓝色图可拍。
     */
    public void ensurePresetMediaFiles() {
        String mediaDir = runtimeConfig.getMediaDir();
        if (mediaDir == null || mediaDir.isBlank()) {
            return; // 未配置，保持 TC-MEDIA-012 降级行为，不预置
        }
        try {
            Path dir = Path.of(mediaDir);
            Files.createDirectories(dir);
            if (!mediaUploader.listMediaFiles(mediaDir).isEmpty()) {
                replaceLegacyPlaceholder(dir);
                log.debug("media-dir 已有媒体文件，跳过示例预置: {}", mediaDir);
                return;
            }
            for (MediaUploader.PresetSample sample : MediaUploader.presetSamples()) {
                extract(sample.resource(), dir.resolve(sample.fileName()));
            }
            log.info("media-dir 无媒体文件，已预置示例照片/视频（模拟拍照+录像素材）: {}", mediaDir);
        } catch (Exception e) {
            log.warn("预置示例媒体文件失败（不影响应用运行）: {} - {}", mediaDir, e.getMessage());
        }
    }

    /**
     * 把旧版本预置的纯色占位图替换为真实示例照片。
     * <p>仅当 {@code sample-photo.jpg} 的 SHA-256 与旧占位图一致时替换，
     * 用户自己放的同名文件（内容不同）不动。
     * <p>老部署中通常只有该占位图 + 示例视频，替换后补上新增的两张示例照片，
     * 让一次任务的多次拍照不再全是同一张图。
     */
    private void replaceLegacyPlaceholder(Path dir) {
        Path legacy = dir.resolve(LEGACY_PLACEHOLDER_NAME);
        if (!Files.isRegularFile(legacy)) {
            return;
        }
        try {
            if (!LEGACY_PLACEHOLDER_SHA256.equals(sha256Hex(legacy))) {
                return; // 用户自己的同名照片，保持原样
            }
            // 占位图已存在，extract 会跳过；这里必须强制覆盖
            copyResource("media-samples/sample-photo.jpg", legacy);
            log.info("已把旧版纯色占位图替换为真实示例照片: {}", legacy);
            for (MediaUploader.PresetSample sample : MediaUploader.presetSamples()) {
                if (sample.fileName().startsWith("sample-photo")) {
                    extract(sample.resource(), dir.resolve(sample.fileName()));
                }
            }
        } catch (Exception e) {
            log.warn("替换旧版占位图失败（不影响应用运行）: {} - {}", legacy, e.getMessage());
        }
    }

    /** 计算文件 SHA-256 十六进制摘要。 */
    private static String sha256Hex(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * 从 classpath 解出单个内置资源到目标路径（已存在时跳过，幂等）。
     */
    private void extract(String classpath, Path target) throws Exception {
        if (Files.exists(target)) {
            return;
        }
        copyResource(classpath, target);
        log.info("已预置示例媒体文件: {}", target);
    }

    /** 解出资源并覆盖目标文件。 */
    private void copyResource(String classpath, Path target) throws Exception {
        try (InputStream in = new ClassPathResource(classpath).getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
