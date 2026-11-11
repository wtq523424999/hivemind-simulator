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

package ltd.cdmi.hivemind.simulator.config;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.multipart.MultipartResolver;
import org.springframework.web.multipart.support.StandardServletMultipartResolver;

/**
 * multipart 解析器配置：对控制台代理路径跳过解析。
 *
 * <p>为什么需要：{@link StandardServletMultipartResolver} 会把 {@code multipart/form-data}
 * 请求体解析成 part，之后 {@code request.getInputStream()} 就变成空流。
 * 机库控制台代理（{@code /fleet/{id}/...}）必须把请求体<b>原样</b>转发给目标实例，
 * 否则「上传媒体 / 上传推流视频」经过代理后会报
 * {@code MissingServletRequestPartException: Required part 'file' is not present}。</p>
 *
 * <p>只跳过代理路径，其余路径保持框架默认行为（{@code /api/media/files/upload} 等
 * 仍按 {@code MultipartFile} 正常接收）。Bean 名必须是 {@code multipartResolver}，
 * 这是 Spring Boot {@code MultipartAutoConfiguration} 的覆盖约定。</p>
 */
@Configuration
public class MultipartConfig {

    /** 控制台代理路径前缀，与 FleetConsoleProxyController.PATH_PREFIX 保持一致。 */
    private static final String FLEET_PROXY_PREFIX = "/fleet/";

    @Bean(name = "multipartResolver")
    public MultipartResolver multipartResolver() {
        return new StandardServletMultipartResolver() {
            @Override
            public boolean isMultipart(HttpServletRequest request) {
                String path = request.getRequestURI();
                String contextPath = request.getContextPath();
                if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
                    path = path.substring(contextPath.length());
                }
                if (path.startsWith(FLEET_PROXY_PREFIX)) {
                    // 不解析：保证代理能读到原始请求体并原样转发
                    return false;
                }
                return super.isMultipart(request);
            }
        };
    }
}
