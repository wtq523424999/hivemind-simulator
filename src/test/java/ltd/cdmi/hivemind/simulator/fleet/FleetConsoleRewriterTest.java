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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 控制台代理路径改写测试（TC-FLEET-801~808）。
 *
 * <p>覆盖：HTML 的 href/src 改写、内联脚本里的根路径字面量、monitor 页返回首页链接、
 * {@code '/'} 普通斜杠不被误改、协议相对地址不动、脚本改写与存储隔离注入。</p>
 */
class FleetConsoleRewriterTest {

    private static final String PREFIX = "/fleet/dock-abc12345";

    @DisplayName("TC-FLEET-801：HTML 的 href/src 根路径前插实例前缀")
    @Test
    void rewriteHtmlPrefixesRootAttributes() {
        String html = """
                <html><head>
                <link rel="icon" href="/favicon.svg">
                <link rel="stylesheet" href="/vendor/element-plus/index.css">
                </head><body>
                <script src="/panels/fleet-manager.js"></script>
                </body></html>
                """;
        String out = FleetConsoleRewriter.rewriteHtml(html, PREFIX);

        assertTrue(out.contains("href=\"" + PREFIX + "/favicon.svg\""));
        assertTrue(out.contains("href=\"" + PREFIX + "/vendor/element-plus/index.css\""));
        assertTrue(out.contains("src=\"" + PREFIX + "/panels/fleet-manager.js\""));
    }

    @DisplayName("TC-FLEET-802：返回首页的 href=\"/\" 改写到实例根")
    @Test
    void rewriteHtmlPrefixesBareRootLink() {
        String html = "<a href=\"/\" class=\"nav-link\">模拟器</a>";
        String out = FleetConsoleRewriter.rewriteHtml(html, PREFIX);

        assertTrue(out.contains("href=\"" + PREFIX + "/\""));
    }

    @DisplayName("TC-FLEET-803：内联脚本里的根路径字面量同步改写")
    @Test
    void rewriteHtmlPrefixesInlineScriptLiterals() {
        String html = "<script>fetchJSON('/api/state');window.open(\"/monitor.html\");</script>";
        String out = FleetConsoleRewriter.rewriteHtml(html, PREFIX);

        assertTrue(out.contains("'" + PREFIX + "/api/state'"));
        assertTrue(out.contains("\"" + PREFIX + "/monitor.html\""));
    }

    @DisplayName("TC-FLEET-804：普通 '/' 字面量与 topic 切分逻辑不被改写")
    @Test
    void rewriteKeepsPlainSlashLiterals() {
        String script = "if (topic.endsWith('/' + f)) return false; var sep = '/';";
        String out = FleetConsoleRewriter.rewriteJavaScript(script, PREFIX);

        assertEquals(script, out);
    }

    @DisplayName("TC-FLEET-805：协议相对地址 //host 不被改写")
    @Test
    void rewriteKeepsProtocolRelativeUrls() {
        String html = "<script src=\"//cdn.example.com/lib.js\"></script>";
        String out = FleetConsoleRewriter.rewriteHtml(html, PREFIX);

        assertTrue(out.contains("src=\"//cdn.example.com/lib.js\""));
    }

    @DisplayName("TC-FLEET-806：JavaScript 的根接口路径全部前插实例前缀")
    @Test
    void rewriteJavaScriptPrefixesApiLiterals() {
        String script = "await fetchJSON('/api/fleet/statuses');"
                + "xhr.open('POST', '/api/live/videos/upload');"
                + "const u = `/api/logs?beforeTime=${t}`;";
        String out = FleetConsoleRewriter.rewriteJavaScript(script, PREFIX);

        assertTrue(out.contains("'" + PREFIX + "/api/fleet/statuses'"));
        assertTrue(out.contains("'" + PREFIX + "/api/live/videos/upload'"));
        assertTrue(out.contains("`" + PREFIX + "/api/logs?beforeTime="));
    }

    @DisplayName("TC-FLEET-807：注入的存储隔离脚本按实例 id 隔离键")
    @Test
    void rewriteHtmlInjectsScopedStorage() {
        String html = "<html><head><meta charset=\"UTF-8\"></head><body></body></html>";
        String out = FleetConsoleRewriter.rewriteHtml(html, PREFIX);

        assertTrue(out.contains("fleet:dock-abc12345:"));
        // 必须插在 head 最前面，页面脚本执行前就已生效
        int scriptAt = out.indexOf("fleet:dock-abc12345:");
        int metaAt = out.indexOf("<meta charset");
        assertTrue(scriptAt > 0 && scriptAt < metaAt);
    }

    @DisplayName("TC-FLEET-808：机库管理的控制台链接不受代理改写影响（仍指向入口根）")
    @Test
    void rewriteDoesNotPrefixFleetLinks() {
        String script = "window.open(`/fleet/${encodeURIComponent(row.id)}/`, '_blank');"
                + "const p = '/fleet/dock-other/';";
        String out = FleetConsoleRewriter.rewriteJavaScript(script, PREFIX);

        assertFalse(out.contains(PREFIX + "/fleet/"));
        assertEquals(script, out);
    }
}
