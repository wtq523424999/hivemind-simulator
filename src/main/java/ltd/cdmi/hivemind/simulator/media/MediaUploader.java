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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * S3 兼容协议文件上传器。
 * <p>使用 STS 临时凭证将本地文件上传到对象存储（阿里云 OSS / 华为云 OBS / AWS S3 / MinIO），
 * 供 {@link MediaUploadSimulator} 在媒体上传流程中调用。</p>
 *
 * <p>核实依据：<a href="https://developer.dji.com/doc/cloud-api-tutorial/cn/feature-set/pilot-feature-set/pilot-media-management.html">DJI 媒体管理交互时序</a>
 * 中「执行文件上传」步骤——机场使用 storage_config_get 返回的临时凭证直接上传文件到对象存储。</p>
 *
 * <p>Region 解析策略：优先从 endpoint 提取完整区域名（解决 OSS 短格式 region 如 "hz" 导致签名不匹配），
 * 回退到 region 字段，最后默认 us-east-1（MinIO 不校验区域）。</p>
 *
 * <p>每次上传创建独立的 S3Client（STS 凭证有效期短，不同任务凭证不同），
 * 上传完成后关闭客户端释放资源。</p>
 */
@Component
public class MediaUploader {

    private static final Logger log = LoggerFactory.getLogger(MediaUploader.class);

    /** 支持的媒体文件扩展名（图片 + 视频） */
    private static final List<String> SUPPORTED_EXTENSIONS = List.of(
            ".jpg", ".jpeg", ".png", ".dng", ".tif", ".tiff",
            ".mp4", ".mov", ".avi"
    );

    /** 图片扩展名（航点拍照只取图片，目录里的视频不能当照片上传） */
    private static final List<String> IMAGE_EXTENSIONS = List.of(
            ".jpg", ".jpeg", ".png", ".dng", ".tif", ".tiff"
    );

    /** JAR 内置示例照片（media-dir 无用户照片时按顺序循环，作为航点拍照的图片内容） */
    private static final List<String> SAMPLE_PHOTO_RESOURCES = List.of(
            "media-samples/sample-photo.jpg",
            "media-samples/sample-photo-2.jpg",
            "media-samples/sample-photo-3.jpg"
    );

    /** JAR 内置示例视频（仅随示例预置落盘，供通用媒体上传演示） */
    private static final String SAMPLE_VIDEO_RESOURCE = "media-samples/sample-video.mp4";

    /** 预置样例文件名：与用户自己放入的文件区分，用户照片存在时不再使用这些样例 */
    private static final List<String> PRESET_FILE_NAMES = List.of(
            "sample-photo.jpg", "sample-photo-2.jpg", "sample-photo-3.jpg", "sample-video.mp4"
    );

    /**
     * 预置样例清单（classpath 资源 → 落盘文件名），供 {@link MediaSampleInitializer} 预置。
     */
    public static List<PresetSample> presetSamples() {
        List<PresetSample> samples = new ArrayList<>();
        for (String resource : SAMPLE_PHOTO_RESOURCES) {
            samples.add(new PresetSample(resource, fileNameOf(resource)));
        }
        samples.add(new PresetSample(SAMPLE_VIDEO_RESOURCE, fileNameOf(SAMPLE_VIDEO_RESOURCE)));
        return samples;
    }

    /** 是否为 JAR 内置预置样例文件（按文件名判断）。 */
    public static boolean isPresetFile(Path file) {
        return file != null && PRESET_FILE_NAMES.contains(file.getFileName().toString());
    }

    private static String fileNameOf(String resource) {
        return resource.substring(resource.lastIndexOf('/') + 1);
    }

    /** 预置样例：{@code resource} 为 classpath 资源，{@code fileName} 为落盘文件名。 */
    public record PresetSample(String resource, String fileName) {}

    /**
     * 生成一张可上传的示例照片文件（JAR 内置资源解出到临时文件，调用方负责删除）。
     * <p>航点拍照在 media-dir 无可用照片时仍需上传真实字节，否则平台按 object_key 取图会 404。
     * <p>按 {@code index} 轮换不同示例照片，使同一任务的多次拍照不全是同一张图。
     *
     * @param index 拍照序号（负值也可，取模后落到合法区间）
     * @return 临时 JPEG 文件
     * @throws IOException 资源缺失或写入失败
     */
    public Path samplePhoto(int index) throws IOException {
        String resource = SAMPLE_PHOTO_RESOURCES.get(
                Math.floorMod(index, SAMPLE_PHOTO_RESOURCES.size()));
        Path temp = Files.createTempFile("dji-photo-", ".jpg");
        try (InputStream in = new ClassPathResource(resource).getInputStream()) {
            Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
        return temp;
    }

    /**
     * 上传单个文件到对象存储。
     *
     * @param filePath   本地文件路径
     * @param config     STS 凭证配置
     * @param objectKey  对象存储 Key（不含 bucket，含完整路径）
     * @return true=上传成功，false=上传失败（凭证无效、文件不存在、网络异常等）
     */
    public boolean upload(Path filePath, StorageConfig config, String objectKey) {
        if (!config.isValid()) {
            log.warn("STS 凭证无效，跳过文件上传: bucket={}, endpoint={}", config.bucket(), config.endpoint());
            return false;
        }
        if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
            log.warn("文件不存在或非普通文件，跳过上传: {}", filePath);
            return false;
        }

        S3Client client = null;
        try {
            client = buildClient(config);
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(config.bucket())
                    .key(objectKey)
                    .build();
            client.putObject(request, filePath);
            log.info("文件上传成功: {} -> bucket={}, key={}", filePath.getFileName(), config.bucket(), objectKey);
            return true;
        } catch (Exception e) {
            log.warn("文件上传失败: {} - {}", filePath.getFileName(), e.getMessage());
            return false;
        } finally {
            if (client != null) {
                client.close();
            }
        }
    }

    /**
     * 扫描媒体文件目录，返回支持的图片/视频文件列表。
     * @param mediaDir 媒体文件目录路径
     * @return 文件路径列表（按文件名排序）；目录无效或为空返回空列表
     */
    public List<Path> listMediaFiles(String mediaDir) {
        return listFiles(mediaDir, SUPPORTED_EXTENSIONS);
    }

    /**
     * 扫描目录中的图片文件（jpg/jpeg/png/dng/tif/tiff），视频不计入。
     * <p>航点拍照产出的是照片，目录里的 mp4/mov/avi 不能当作照片上传。
     *
     * @param mediaDir 媒体文件目录路径
     * @return 图片文件列表（按文件名排序）；目录无效或为空返回空列表
     */
    public List<Path> listPhotoFiles(String mediaDir) {
        return listFiles(mediaDir, IMAGE_EXTENSIONS);
    }

    /**
     * 扫描目录中用户自己放入的图片文件（排除 JAR 内置预置样例），按文件名排序。
     * <p>用户照片优先于示例图：只要目录里有自己上传/放入的照片，就用它们作为拍照内容，
     * 避免预置的示例图混进素材池被轮换取到。
     *
     * @param mediaDir 媒体文件目录路径
     * @return 用户图片文件列表；目录无效或为空返回空列表
     */
    public List<Path> listUserPhotoFiles(String mediaDir) {
        return listPhotoFiles(mediaDir).stream()
                .filter(p -> !isPresetFile(p))
                .toList();
    }

    /**
     * 按扩展名白名单扫描目录。
     */
    private List<Path> listFiles(String mediaDir, List<String> extensions) {
        if (mediaDir == null || mediaDir.isBlank()) {
            return List.of();
        }
        Path dir = Path.of(mediaDir);
        if (!Files.isDirectory(dir)) {
            log.warn("media-dir 不是有效目录: {}", mediaDir);
            return List.of();
        }
        List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        return extensions.stream().anyMatch(name::endsWith);
                    })
                    .sorted()
                    .forEach(files::add);
        } catch (IOException e) {
            log.warn("扫描媒体目录失败: {} - {}", mediaDir, e.getMessage());
        }
        return files;
    }

    /**
     * 构建 S3 兼容客户端。
     * <p>按 provider 差异配置：
     * <ul>
     *   <li>minio：启用 path-style 访问（MinIO 不支持 virtual-hosted style）</li>
     *   <li>ali/aws/obs 及其他：使用默认 virtual-hosted style</li>
     * </ul>
     * <p>Region 通过 {@link #resolveRegion} 解析，优先从 endpoint 提取完整区域名。
     */
    private S3Client buildClient(StorageConfig config) {
        String endpoint = ensureScheme(config.endpoint());
        Region region = Region.of(resolveRegion(config));

        AwsSessionCredentials creds = AwsSessionCredentials.create(
                config.accessKeyId(),
                config.accessKeySecret(),
                config.securityToken() != null ? config.securityToken() : "");

        var builder = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(region)
                .credentialsProvider(StaticCredentialsProvider.create(creds));

        // MinIO 必须使用 path-style，OSS/OBS/S3 默认 virtual-hosted
        if ("minio".equals(config.provider())) {
            builder.serviceConfiguration(S3Configuration.builder()
                    .pathStyleAccessEnabled(true)
                    .build());
        }

        return builder.build();
    }

    /**
     * 解析 S3 签名区域。
     * <p>优先从 endpoint 提取完整区域名（解决 OSS 短格式 region 如 "hz" 导致签名不匹配），
     * 回退到 region 字段，最后默认 us-east-1（MinIO 不校验区域）。</p>
     *
     * <p>提取规则：</p>
     * <ul>
     *   <li>阿里云 OSS：{@code oss-cn-hangzhou.aliyuncs.com} → {@code cn-hangzhou}</li>
     *   <li>华为云 OBS：{@code obs.cn-north-1.myhuaweicloud.com} → {@code cn-north-1}</li>
     *   <li>AWS S3：{@code s3.us-east-1.amazonaws.com} → {@code us-east-1}</li>
     * </ul>
     */
    private String resolveRegion(StorageConfig config) {
        // 优先从 endpoint 提取（解决 OSS region 短格式如 "hz" 导致签名不匹配）
        String extracted = extractRegionFromEndpoint(config.endpoint());
        if (extracted != null && !extracted.isBlank()) {
            return extracted;
        }
        // 回退到 region 字段
        if (config.region() != null && !config.region().isBlank()) {
            return config.region();
        }
        // 默认值（MinIO 等不关心 region 的服务）
        return "us-east-1";
    }

    /**
     * 从 endpoint URL 提取区域名。
     * @param endpoint 对象存储 endpoint（如 https://oss-cn-hangzhou.aliyuncs.com）
     * @return 提取的区域名（如 cn-hangzhou）；无法识别返回 null
     */
    private String extractRegionFromEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return null;
        }
        String host = endpoint;
        // 去除协议头
        int schemeIdx = host.indexOf("://");
        if (schemeIdx >= 0) {
            host = host.substring(schemeIdx + 3);
        }
        // 去除端口和路径
        int portIdx = host.indexOf(':');
        if (portIdx >= 0) {
            host = host.substring(0, portIdx);
        }
        int pathIdx = host.indexOf('/');
        if (pathIdx >= 0) {
            host = host.substring(0, pathIdx);
        }

        // 阿里云 OSS: oss-cn-hangzhou.aliyuncs.com → cn-hangzhou
        if (host.startsWith("oss-") && host.contains(".aliyuncs.com")) {
            return host.substring(4, host.indexOf(".aliyuncs.com"));
        }
        // 华为云 OBS: obs.cn-north-1.myhuaweicloud.com → cn-north-1
        if (host.startsWith("obs.") && host.contains(".myhuaweicloud.com")) {
            return host.substring(4, host.indexOf(".myhuaweicloud.com"));
        }
        // AWS S3: s3.us-east-1.amazonaws.com → us-east-1
        if (host.startsWith("s3.") && host.contains(".amazonaws.com")) {
            return host.substring(3, host.indexOf(".amazonaws.com"));
        }
        // AWS S3 旧格式: s3-us-east-1.amazonaws.com → us-east-1
        if (host.startsWith("s3-") && host.contains(".amazonaws.com")) {
            return host.substring(3, host.indexOf(".amazonaws.com"));
        }
        return null;
    }

    /**
     * 确保 endpoint 包含协议头（http:// 或 https://）。
     * <p>部分平台返回的 endpoint 可能不含 scheme，S3 SDK 要求 URI 包含 scheme。</p>
     */
    private String ensureScheme(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            return "https://s3.amazonaws.com";
        }
        if (!endpoint.startsWith("http://") && !endpoint.startsWith("https://")) {
            return "https://" + endpoint;
        }
        return endpoint;
    }
}
