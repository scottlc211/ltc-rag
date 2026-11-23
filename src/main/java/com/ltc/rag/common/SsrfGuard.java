package com.ltc.rag.common;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;

/**
 * Webhook 回调地址 SSRF（服务端请求伪造）安全校验工具。
 *
 * <p>为什么必须在提交与投递两个阶段同时做校验：
 * <ul>
 *   <li><b>提交时校验</b>：第一时间向调用方报错（400 BAD_CALLBACK_URL），阻止明显非法的内网/环回地址入库；</li>
 *   <li><b>投递前二次校验</b>：防御 DNS Rebinding（提交时域名解析到公网 IP，3 秒后投递时被篡改解析到 127.0.0.1 或容器内网）。</li>
 * </ul>
 */
public final class SsrfGuard {

    /** Docker Compose 内网常见服务名与本机别名黑名单。 */
    private static final Set<String> BLOCKED_HOSTS = Set.of(
            "localhost",
            "mysql",
            "qdrant",
            "app",
            "host.docker.internal",
            "gateway.docker.internal",
            "metadata.google.internal"
    );

    private SsrfGuard() {
    }

    /**
     * 校验回调 URL 的格式与目标主机安全性。
     *
     * @param rawUrl         原始回调地址
     * @param allowPrivateIp 是否允许私网/环回地址（生产环境应为 false，仅内网联调时可显式开启）
     * @return 规范化后的 URL 字符串；若入参为空则返回 null
     */
    public static String validateCallbackUrl(String rawUrl, boolean allowPrivateIp) {
        if (rawUrl == null || rawUrl.isBlank()) {
            return null;
        }
        URI uri;
        try {
            uri = URI.create(rawUrl.strip());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("BAD_CALLBACK_URL", "callbackUrl 格式非法：" + rawUrl);
        }

        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw ApiException.badRequest("BAD_CALLBACK_URL", "callbackUrl 必须是 http 或 https 地址");
        }
        if (uri.getUserInfo() != null) {
            throw ApiException.badRequest("BAD_CALLBACK_URL", "callbackUrl 不允许包含用户信息（user:pass@）");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw ApiException.badRequest("BAD_CALLBACK_URL", "callbackUrl 缺少有效主机名");
        }

        if (!allowPrivateIp) {
            assertPublicHost(host);
        }
        return uri.toString();
    }

    /**
     * 断言目标主机不是本机、Docker 容器服务名、云厂商元数据地址或内网保留 IP。
     */
    public static void assertPublicHost(String host) {
        String normalized = host.strip().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        if (BLOCKED_HOSTS.contains(normalized) || normalized.endsWith(".internal") || normalized.endsWith(".local")) {
            throw ApiException.badRequest("SSRF_BLOCKED",
                    "安全限制：callbackUrl 禁止指向内部服务或本地主机（" + host + "）");
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(normalized);
        } catch (UnknownHostException e) {
            throw ApiException.badRequest("BAD_CALLBACK_URL", "无法解析回调域名：" + host);
        }

        for (InetAddress addr : addresses) {
            if (isForbiddenAddress(addr)) {
                throw ApiException.badRequest("SSRF_BLOCKED",
                        "安全限制：callbackUrl 解析到内网或保留地址（" + host + " -> " + addr.getHostAddress() + "）");
            }
        }
    }

    /**
     * 判断 IP 是否属于环回、私网、链路本地、云元数据（169.254.0.0/16）、CGNAT（100.64.0.0/10）等受限网段。
     */
    public static boolean isForbiddenAddress(InetAddress addr) {
        if (addr.isAnyLocalAddress()
                || addr.isLoopbackAddress()
                || addr.isLinkLocalAddress()
                || addr.isSiteLocalAddress()
                || addr.isMulticastAddress()) {
            return true;
        }
        byte[] raw = addr.getAddress();
        if (addr instanceof Inet4Address && raw.length == 4) {
            int b0 = raw[0] & 0xFF;
            int b1 = raw[1] & 0xFF;
            // 0.0.0.0/8 当前网络
            if (b0 == 0) {
                return true;
            }
            // 100.64.0.0/10 (Carrier-Grade NAT / 云厂商内网常见网段，如阿里云/Tailscale)
            if (b0 == 100 && (b1 >= 64 && b1 <= 127)) {
                return true;
            }
            // 169.254.0.0/16 云厂商实例元数据服务 (AWS/阿里云/腾讯云 169.254.169.254)
            if (b0 == 169 && b1 == 254) {
                return true;
            }
            // 198.18.0.0/15 基准测试专用网段
            if (b0 == 198 && (b1 == 18 || b1 == 19)) {
                return true;
            }
            // 240.0.0.0/4 保留地址及广播地址
            if (b0 >= 240) {
                return true;
            }
        } else if (addr instanceof Inet6Address && raw.length == 16) {
            int b0 = raw[0] & 0xFF;
            // fc00::/7 IPv6 唯一本地地址 (ULA)
            if ((b0 & 0xFE) == 0xFC) {
                return true;
            }
            // ::ffff:0:0/96 IPv4 映射地址，防止通过 [::ffff:127.0.0.1] 绕过
            if (isIpv4MappedLoopbackOrPrivate(raw)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isIpv4MappedLoopbackOrPrivate(byte[] raw) {
        for (int i = 0; i < 10; i++) {
            if (raw[i] != 0) {
                return false;
            }
        }
        if ((raw[10] & 0xFF) != 0xFF || (raw[11] & 0xFF) != 0xFF) {
            return false;
        }
        int b0 = raw[12] & 0xFF;
        int b1 = raw[13] & 0xFF;
        return b0 == 0
                || b0 == 10
                || b0 == 127
                || (b0 == 172 && b1 >= 16 && b1 <= 31)
                || (b0 == 192 && b1 == 168)
                || (b0 == 169 && b1 == 254)
                || (b0 == 100 && b1 >= 64 && b1 <= 127);
    }
}
