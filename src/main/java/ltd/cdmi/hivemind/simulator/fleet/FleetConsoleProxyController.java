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

package ltd.cdmi.hivemind.simulator.fleet;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executors;

/**
 * 机库控制台同源反向代理（{@code /fleet/{id}/...}）。
 *
 * <p>解决的问题：机库清单里登记的 {@code host}/{@code port} 是<b>服务端视角</b>的地址
 * （容器部署时通常是 {@code 容器名:9090}，见 {@link FleetInstance}）。这个地址用来做
 * 服务端 HTTP 调用是正确的，但不能直接交给浏览器——浏览器解析不了 Docker 内部主机名，
 * 而且对外通常只映射了一个入口端口，其他实例的宿主机端口（9091/9100…）在外部不可达。</p>
 *
 * <p>因此把每个实例的控制台挂到<b>当前入口的子路径</b>下：
 * {@code http://<当前入口>/fleet/{id}/} 由本代理转发到该实例的内部地址。
 * 这样无论从 9513、9090 还是任意反代域名访问，点「控制台」始终可用，也不必额外开放端口。</p>
 *
 * <p>与 {@link FleetInstance#internalBaseUrl(int)} 一致，本机实例（selfInstance）走内部回环，
 * 避免容器内访问宿主机映射端口不通。响应按类型处理：</p>
 * <ul>
 *   <li>HTML / JavaScript：按 {@link FleetConsoleRewriter} 把根绝对路径前插实例前缀，
 *       否则页面里的 {@code /api/...} 会打到当前实例而不是目标实例；</li>
 *   <li>其他（JSON、图片、SSE 事件流、文件上传下载）：边收边转，不缓冲。</li>
 * </ul>
 *
 * <p>转发是<b>同步、流式</b>的：上传（最大 512MB）与 SSE（一键安装 FFmpeg 的进度流）
 * 都不会被额外的异步请求超时截断；连接建立本身有 3 秒超时，实例容器不存在时快速失败为 502。
 * 代价是每个代理请求占用一个容器线程，对模拟器这种小规模控制台可以接受。</p>
 *
 * <p>安全边界：模拟器本身没有任何鉴权，此代理与既有的 {@code /api/fleet/*} 同权限级；
 * 只能访问清单里已登记实例的控制台，不接受任意 URL 入参，因此不构成通用开放代理。</p>
 */
@RestController
public class FleetConsoleProxyController {

    private static final Logger log = LoggerFactory.getLogger(FleetConsoleProxyController.class);

    /** 代理路径前缀（浏览器可见）。 */
    public static final String PATH_PREFIX = "/fleet";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);

    /**
     * 不向上游转发的逐跳头与主机相关头：
     * {@code Host} 由 HttpClient 按目标 URI 自动生成（JDK 也禁止手工设置该受限头）；
     * {@code Origin} 改写成目标实例地址，否则上游按同源校验可能拒绝；
     * {@code Accept-Encoding} 交给 HttpClient 自行协商，避免拿到压缩体后再被二次编码；
     * {@code Content-Length} 由 HttpClient 按实际 body 重新计算。
     */
    private static final Set<String> SKIPPED_REQUEST_HEADERS = Set.of(
            "host", "origin", "referer", "connection", "keep-alive", "proxy-authenticate",
            "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade",
            "content-length", "accept-encoding", "expect");

    /** 不回传给浏览器的逐跳头与编码头（内容长度在未改写时按上游原值回传）。 */
    private static final Set<String> SKIPPED_RESPONSE_HEADERS = Set.of(
            "connection", "keep-alive", "proxy-authenticate", "proxy-authorization",
            "te", "trailer", "transfer-encoding", "upgrade", "content-encoding", "content-length");

    private final FleetService fleetService;
    private final HttpClient httpClient;
    private final int selfPort;

    public FleetConsoleProxyController(FleetService fleetService,
                                       @Value("${server.port:9090}") int selfPort) {
        this.fleetService = fleetService;
        this.selfPort = selfPort;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                // 控制台页面会并发加载多个静态资源，连接池给足避免排队
                .executor(Executors.newFixedThreadPool(8, runnable -> {
                    Thread thread = new Thread(runnable, "fleet-console-proxy");
                    thread.setDaemon(true);
                    return thread;
                }))
                .build();
    }

    /**
     * 代理实例控制台的全部请求（页面、静态资源、REST 接口）。
     *
     * <p>用 {@code /fleet/**} 一次覆盖而不是逐个接口注册：控制台前后端接口很多，
     * 逐一列举必然随版本演进漏掉新接口。不限制请求方法，POST/PUT/DELETE 一并转发。</p>
     */
    @RequestMapping("/fleet/**")
    public void proxy(HttpServletRequest request, HttpServletResponse response) throws IOException {
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        if (!path.startsWith(PATH_PREFIX)) {
            writeJsonError(response, HttpServletResponse.SC_NOT_FOUND, "代理路径非法: " + path);
            return;
        }

        // /fleet/{id}/rest... → 实例 id + 上游路径
        String rest = path.substring(PATH_PREFIX.length());
        if (rest.startsWith("/")) {
            rest = rest.substring(1);
        }
        int slash = rest.indexOf('/');
        String id = slash < 0 ? rest : rest.substring(0, slash);
        String upstreamPath = slash < 0 ? "/" : rest.substring(slash);

        FleetInstance instance = fleetService.findInstance(id).orElse(null);
        if (instance == null) {
            writeJsonError(response, HttpServletResponse.SC_NOT_FOUND, "机库不存在或未登记: " + id);
            return;
        }

        String query = request.getQueryString();
        String targetUrl = instance.internalBaseUrl(selfPort) + upstreamPath
                + (query == null || query.isBlank() ? "" : "?" + query);

        HttpResponse<InputStream> upstream;
        try {
            upstream = httpClient.send(buildRequest(request, targetUrl, instance),
                    HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            log.debug("控制台代理失败: id={}, url={}, reason={}", id, targetUrl, e.getMessage());
            writeJsonError(response, HttpServletResponse.SC_BAD_GATEWAY, "实例不可达: " + describe(e));
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            writeJsonError(response, HttpServletResponse.SC_BAD_GATEWAY, "代理请求被中断");
            return;
        }

        try (InputStream body = upstream.body()) {
            String contentType = upstream.headers().firstValue("content-type").orElse(null);
            boolean rewritable = isRewritable(contentType);
            response.setStatus(upstream.statusCode());
            upstream.headers().map().forEach((name, values) -> {
                if (!SKIPPED_RESPONSE_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                    values.forEach(value -> response.addHeader(name, value));
                }
            });

            if (rewritable) {
                // 页面与脚本体积很小：整体读出改写后再写回。改写会改变字节数，不设 Content-Length。
                byte[] raw = body.readAllBytes();
                String text = new String(raw, StandardCharsets.UTF_8);
                String prefix = PATH_PREFIX + "/" + instance.id();
                String rewritten = isHtml(contentType)
                        ? FleetConsoleRewriter.rewriteHtml(text, prefix)
                        : FleetConsoleRewriter.rewriteJavaScript(text, prefix);
                byte[] payload = rewritten.getBytes(StandardCharsets.UTF_8);
                response.setContentLength(payload.length);
                OutputStream output = response.getOutputStream();
                if (!"HEAD".equalsIgnoreCase(request.getMethod())) {
                    output.write(payload);
                }
                output.flush();
                return;
            }

            // Content-Length 未在跳过表里以外的方式保留：上游给出就用，便于浏览器显示下载进度
            upstream.headers().firstValue("content-length")
                    .flatMap(FleetConsoleProxyController::parseLong)
                    .ifPresent(response::setContentLengthLong);

            OutputStream output = response.getOutputStream();
            if (!"HEAD".equalsIgnoreCase(request.getMethod())) {
                // 分块转发并及时 flush：SSE（事件流）与上传进度都要求数据到达即推给浏览器，
                // 整体 transferTo 会被容器输出缓冲攒住，表现为"进度不动"。
                byte[] buffer = new byte[8192];
                int read;
                while ((read = body.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                    output.flush();
                }
            }
            output.flush();
        } catch (IOException e) {
            // 客户端提前关闭（切换页面、取消下载）会走到这里，不是服务端错误
            log.debug("控制台代理输出中断: id={}, reason={}", id, e.getMessage());
        }
    }

    private HttpRequest buildRequest(HttpServletRequest request, String targetUrl,
                                     FleetInstance instance) {
        String method = request.getMethod();
        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(targetUrl));

        if ("GET".equals(method) || "HEAD".equals(method) || "DELETE".equals(method)
                || "OPTIONS".equals(method)) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            // 上传（视频最大 512MB）边读边发，不先读进内存。
            // BodyPublishers.ofInputStream 的 Supplier 不允许抛受检异常，这里包一层。
            builder.method(method, HttpRequest.BodyPublishers.ofInputStream(() -> {
                try {
                    return request.getInputStream();
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }));
        }

        Enumeration<String> names = request.getHeaderNames();
        if (names != null) {
            while (names.hasMoreElements()) {
                String name = names.nextElement();
                if (SKIPPED_REQUEST_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                Enumeration<String> values = request.getHeaders(name);
                while (values.hasMoreElements()) {
                    builder.header(name, values.nextElement());
                }
            }
        }

        // 上游实例按自身地址判断同源（SSE、上传、部分接口），Origin 必须改成目标地址。
        // Host 是 JDK HttpClient 的受限头，由它按请求 URI 自动生成，不能手工设置。
        URI target = URI.create(instance.internalBaseUrl(selfPort));
        builder.header("Origin", target.getScheme() + "://" + target.getAuthority());
        return builder.build();
    }

    private static java.util.Optional<Long> parseLong(String value) {
        try {
            return java.util.Optional.of(Long.parseLong(value.trim()));
        } catch (NumberFormatException e) {
            return java.util.Optional.empty();
        }
    }

    private static boolean isHtml(String contentType) {
        String subtype = mediaSubtype(contentType);
        return "html".equals(subtype) || "xhtml".equals(subtype);
    }

    /** 需要改写根绝对路径的响应类型：HTML 与 JavaScript。 */
    private static boolean isRewritable(String contentType) {
        String subtype = mediaSubtype(contentType);
        return "html".equals(subtype) || "xhtml".equals(subtype)
                || "javascript".equals(subtype) || "ecmascript".equals(subtype);
    }

    private static String mediaSubtype(String contentType) {
        if (contentType == null) {
            return "";
        }
        int slash = contentType.indexOf('/');
        if (slash < 0) {
            return "";
        }
        int end = contentType.indexOf(';', slash);
        String subtype = end < 0 ? contentType.substring(slash + 1) : contentType.substring(slash + 1, end);
        return subtype.trim().toLowerCase(Locale.ROOT);
    }

    private static void writeJsonError(HttpServletResponse response, int status, String message)
            throws IOException {
        byte[] payload = ("{\"success\":false,\"message\":\"" + escapeJson(message) + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.setContentLength(payload.length);
        response.getOutputStream().write(payload);
    }

    private static String describe(Exception e) {
        String message = e.getMessage();
        if (e.getCause() != null && e.getCause().getMessage() != null) {
            message = message + " - " + e.getCause().getMessage();
        }
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    private static String escapeJson(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
    }

    /** 浏览器可见的控制台路径（前端「控制台」按钮使用）。 */
    public static String consolePath(String id) {
        return PATH_PREFIX + "/" + id + "/";
    }
}
