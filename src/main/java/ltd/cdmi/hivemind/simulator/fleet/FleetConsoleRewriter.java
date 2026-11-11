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

import java.util.regex.Pattern;

/**
 * 控制台代理的 HTML / JS 重写器。
 *
 * <p>模拟器控制台的页面与脚本全部使用<b>根绝对路径</b>（{@code /api/...}、{@code /vendor/...}、
 * {@code /panels/...}、{@code /fleet/...}）。这类地址不受 {@code <base>} 影响，若把页面挂到
 * {@code /fleet/{id}/} 子路径下而不改写，浏览器仍会请求当前入口根下的地址，
 * 拿到的就是当前实例的数据而不是目标实例的。</p>
 *
 * <p>改写规则：</p>
 * <ol>
 *   <li>{@code href="/..."} / {@code src="/..."} 属性：整段路径前插实例前缀
 *       （{@code href="/"} → {@code href="/fleet/{id}/"}）。不处理协议相对地址 {@code //host}；</li>
 *   <li>引号（含模板字符串反引号）紧贴的根绝对路径字面量 {@code '/api/...'}：
 *       前插实例前缀。只匹配紧贴引号的字面量，避免误改 {@code '/'} 这类普通字符串
 *       （消息日志里大量按 {@code '/'} 切分 topic）。</li>
 * </ol>
 *
 * <p><b>刻意不改写 {@code /fleet/}</b>：代理页面的机库管理里，其他机库的「控制台」链接是
 * {@code /fleet/{其他id}/}。它应当落在<b>当前入口</b>上（入口持有完整机库清单），
 * 而不是先绕回被代理的实例——那边有自己的清单，通常为空。</p>
 *
 * <p>这里不做运行时 hook（包装 fetch/XHR/EventSource）：文本改写对静态页面、内联脚本、
 * 外链脚本一视同仁，不依赖页面加载顺序，也不影响根路径直接访问（那条路径不做任何改写）。</p>
 */
final class FleetConsoleRewriter {

    /** 需要从当前入口转发到目标实例的根绝对路径前缀（不可带 {@code //host}）。 */
    private static final String[] ROOT_PATHS = {
            "/api/", "/vendor/", "/panels/", "/monitor.html", "/favicon.svg"
    };

    /** {@code href="/..."} / {@code src="/..."}，排除协议相对地址 {@code //host}。 */
    private static final Pattern ROOT_ATTRIBUTE =
            Pattern.compile("((?:href|src)\\s*=\\s*[\"'])(/(?!/))");

    private FleetConsoleRewriter() {
    }

    /**
     * 改写 HTML：静态资源属性、内联脚本里的根绝对路径，以及返回首页的 {@code href="/"}。
     *
     * <p>另外在页面最早处注入一段存储隔离脚本：代理后所有实例共享同一个 origin，
     * {@code localStorage} 默认也会共享——「注册到第三方平台」的连接配置、已注册标记、
     * 高德 Key 都会串到别的实例上。注入脚本按实例路径给键加前缀，行为回到各实例独立时的样子。</p>
     *
     * @param html   原始 HTML
     * @param prefix 实例前缀，形如 {@code /fleet/dock-xxxxxxxx}（不以 / 结尾）
     */
    static String rewriteHtml(String html, String prefix) {
        // prefix 只含 / - 与字母数字，无需转义；这里必须保留 $1/$2 分组语义
        String rewritten = ROOT_ATTRIBUTE.matcher(html).replaceAll("$1" + prefix + "$2");
        rewritten = prefixQuotedRootPaths(rewritten, prefix);
        return injectStorageScope(rewritten, prefix);
    }

    /**
     * 改写 JavaScript：只处理引号（含模板字符串反引号）开头的根绝对路径字面量。
     *
     * @param script 原始脚本
     * @param prefix 实例前缀，形如 {@code /fleet/dock-xxxxxxxx}
     */
    static String rewriteJavaScript(String script, String prefix) {
        return prefixQuotedRootPaths(script, prefix);
    }

    private static String prefixQuotedRootPaths(String text, String prefix) {
        String result = text;
        for (String path : ROOT_PATHS) {
            result = result.replace("\"" + path, "\"" + prefix + path)
                    .replace("'" + path, "'" + prefix + path)
                    .replace("`" + path, "`" + prefix + path);
        }
        return result;
    }

    /**
     * 注入存储隔离脚本：把 {@code localStorage}/{@code sessionStorage} 的键按实例前缀隔离。
     *
     * <p>代理后所有实例共享同一个 origin，浏览器存储也随之共享。若不隔离，
     * 在 A 实例保存的 MQTT 地址/账号/绑定码会被 B 实例的「注册」弹窗读走，
     * 已注册标记与高德 Key 也会互相覆盖。这里在页面最前面改写 Storage 原型方法，
     * 只加键前缀，页面其余代码（{@code localStorage.getItem('registered')} 等）无需改动。</p>
     *
     * <p>前缀取实例 id：同一实例从任何入口（9090 / 9513 / 域名）打开都得到同一命名空间。</p>
     */
    private static String injectStorageScope(String html, String prefix) {
        String scope = prefix.substring(prefix.lastIndexOf('/') + 1);
        // 用 defineProperty 覆盖实例方法：Storage 支持"命名属性"（localStorage.foo = x 会存一条记录），
        // 直接赋值可能被命名属性写入拦截；defineProperty 明确创建自有属性，语义稳定。
        String script = "<script>(function(){var s='fleet:" + scope + ":';"
                + "['localStorage','sessionStorage'].forEach(function(n){"
                + "var st=window[n];if(!st||st.__fleetScoped)return;"
                + "var get=st.getItem.bind(st),set=st.setItem.bind(st),"
                + "del=st.removeItem.bind(st),key=st.key.bind(st);"
                + "function def(name,fn){try{Object.defineProperty(st,name,"
                + "{value:fn,writable:true,configurable:true});}catch(e){}}"
                + "def('getItem',function(k){return get(s+k)});"
                + "def('setItem',function(k,v){return set(s+k,v)});"
                + "def('removeItem',function(k){return del(s+k)});"
                + "def('key',function(i){var raw=key(i);"
                + "return raw===null?null:(raw.indexOf(s)===0?raw.slice(s.length):raw)});"
                + "try{Object.defineProperty(st,'__fleetScoped',{value:true});}catch(e){}"
                + "});})();</script>";
        int head = html.indexOf("<head>");
        if (head < 0) {
            return script + html;
        }
        return html.substring(0, head + "<head>".length()) + script
                + html.substring(head + "<head>".length());
    }
}
