package org.example.simple.util;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * IP 范围匹配工具类：判断指定 IP 地址是否落在给定的 IP 范围内，同时支持 IPv4 和 IPv6。
 * <p>
 * {@code ipRange} 支持三种表示法：
 * <ul>
 *   <li>CIDR，如 {@code 192.168.1.0/24}、{@code 2001:db8::/32}；</li>
 *   <li>起止地址，如 {@code 192.168.1.1-192.168.1.100}（用英文连字符分隔，两端地址族必须相同）；</li>
 *   <li>单个 IP 地址，视为只包含该地址的范围。</li>
 * </ul>
 * <p>
 * IPv4/IPv6 字面量由本类手动解析为字节数组，不使用 {@link java.net.InetAddress#getByName(String)}——
 * 该方法对不合法的字面量会退化为真正的主机名 DNS 解析，这会让一个格式错误的输入触发一次网络调用；本工具用于访问
 * 控制判断，必须保证任何输入下都是纯本地计算，不发起任何 I/O。不支持主机名解析，也不支持 IPv6 区域 ID（如
 * {@code fe80::1%eth0} 中的 {@code %eth0}）后缀。
 * <p>
 * 地址数值统一用 {@link BigInteger}（无符号）表示，CIDR 与起止地址两种语法最终都转换为同一套
 * "起始数值 ≤ 待检查地址数值 ≤ 结束数值" 的比较逻辑，天然同时覆盖 32 位（IPv4）和 128 位（IPv6）地址。
 * {@code ip} 与 {@code ipRange} 的地址族不一致（如用 IPv4 网段匹配一个 IPv6 地址）判定为不属于，返回
 * {@code false}，不抛出异常——这是正常的"规则不适用"结果，与"范围表达式本身格式错误"需要区分开，后者才是
 * {@link IllegalArgumentException}。
 * <p>
 * 本工具类无状态，所有方法均可被多线程并发调用。
 */
public final class IpRangeCheckUtils {

    private static final int IPV4_BYTE_LENGTH = 4;
    private static final int IPV6_BYTE_LENGTH = 16;
    private static final int IPV4_BIT_LENGTH = 32;
    private static final int IPV6_BIT_LENGTH = 128;
    private static final int IPV6_GROUP_COUNT = IPV6_BYTE_LENGTH / 2;

    private IpRangeCheckUtils() {
    }

    /**
     * 判断 {@code ip} 是否落在 {@code ipRange} 描述的范围内。
     *
     * @param ip 待检查的字面量 IPv4/IPv6 地址，不能为 {@code null} 或空白字符串
     * @param ipRange IP 范围表达式（CIDR、起止地址或单个 IP），不能为 {@code null} 或空白字符串
     * @return {@code ip} 与 {@code ipRange} 地址族一致且数值落在范围内时返回 {@code true}；地址族不一致或
     *     不在范围内返回 {@code false}
     * @throws IllegalArgumentException 当 {@code ip}/{@code ipRange} 为 {@code null}/空白字符串、
     *     {@code ip} 不是合法字面量地址，或 {@code ipRange} 格式非法（非法 CIDR 前缀长度、起始地址大于
     *     结束地址等）时抛出
     */
    public static boolean isInRange(String ip, String ipRange) {
        ParsedAddress target = parseLiteralAddress(ip, "ip");
        ParsedRange range = parseRange(ipRange);
        if (target.family != range.family) {
            return false;
        }
        return target.value.compareTo(range.start) >= 0 && target.value.compareTo(range.end) <= 0;
    }

    /**
     * 判断 {@code ip} 是否落在 {@code ipRanges} 中任意一个范围内。
     *
     * @param ip 待检查的字面量 IPv4/IPv6 地址，不能为 {@code null} 或空白字符串
     * @param ipRanges IP 范围表达式集合，每个元素遵循与 {@link #isInRange} 相同的格式；不能为 {@code null}，
     *     不能包含 {@code null} 元素；可以是空集合（视为没有任何范围匹配，返回 {@code false}）
     * @return 命中集合中任意一个范围时返回 {@code true}；集合为空或全部未命中返回 {@code false}
     * @throws IllegalArgumentException 当 {@code ip} 为 {@code null}/空白字符串/格式非法，
     *     {@code ipRanges} 为 {@code null}，{@code ipRanges} 中存在 {@code null} 元素，或某个元素本身
     *     格式非法时抛出
     */
    public static boolean isInAnyRange(String ip, Collection<String> ipRanges) {
        parseLiteralAddress(ip, "ip");
        if (ipRanges == null) {
            throw new IllegalArgumentException("ipRanges 不能为 null");
        }
        for (String ipRange : ipRanges) {
            if (ipRange == null) {
                throw new IllegalArgumentException("ipRanges 不能包含 null 元素");
            }
        }
        for (String ipRange : ipRanges) {
            if (isInRange(ip, ipRange)) {
                return true;
            }
        }
        return false;
    }

    private enum AddressFamily {
        IPV4,
        IPV6
    }

    private static final class ParsedAddress {

        private final AddressFamily family;
        private final BigInteger value;

        private ParsedAddress(AddressFamily family, BigInteger value) {
            this.family = family;
            this.value = value;
        }
    }

    private static final class ParsedRange {

        private final AddressFamily family;
        private final BigInteger start;
        private final BigInteger end;

        private ParsedRange(AddressFamily family, BigInteger start, BigInteger end) {
            this.family = family;
            this.start = start;
            this.end = end;
        }
    }

    private static ParsedAddress parseLiteralAddress(String text, String paramName) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(paramName + " 不能为 null 或空白字符串");
        }
        if (text.contains(":")) {
            return new ParsedAddress(AddressFamily.IPV6, toUnsignedBigInteger(parseIpv6(text)));
        }
        return new ParsedAddress(AddressFamily.IPV4, toUnsignedBigInteger(parseIpv4(text)));
    }

    private static ParsedRange parseRange(String ipRange) {
        if (ipRange == null || ipRange.isBlank()) {
            throw new IllegalArgumentException("ipRange 不能为 null 或空白字符串");
        }
        int slashIndex = ipRange.indexOf('/');
        if (slashIndex >= 0) {
            return parseCidr(ipRange, slashIndex);
        }
        int dashIndex = ipRange.indexOf('-');
        if (dashIndex >= 0) {
            return parseExplicitRange(ipRange, dashIndex);
        }
        ParsedAddress single = parseLiteralAddress(ipRange, "ipRange");
        return new ParsedRange(single.family, single.value, single.value);
    }

    private static ParsedRange parseCidr(String ipRange, int slashIndex) {
        ParsedAddress address = parseLiteralAddress(ipRange.substring(0, slashIndex), "ipRange");
        String prefixPart = ipRange.substring(slashIndex + 1);

        int totalBits = address.family == AddressFamily.IPV4 ? IPV4_BIT_LENGTH : IPV6_BIT_LENGTH;
        int prefixLength;
        try {
            prefixLength = Integer.parseInt(prefixPart);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("ipRange 的 CIDR 前缀长度不是合法整数: " + ipRange, exception);
        }
        if (prefixLength < 0 || prefixLength > totalBits) {
            throw new IllegalArgumentException("ipRange 的 CIDR 前缀长度超出范围: " + ipRange);
        }

        int hostBits = totalBits - prefixLength;
        BigInteger hostMask = hostBits == 0
                ? BigInteger.ZERO
                : BigInteger.ONE.shiftLeft(hostBits).subtract(BigInteger.ONE);
        BigInteger start = address.value.andNot(hostMask);
        BigInteger end = start.or(hostMask);
        return new ParsedRange(address.family, start, end);
    }

    private static ParsedRange parseExplicitRange(String ipRange, int dashIndex) {
        ParsedAddress start = parseLiteralAddress(ipRange.substring(0, dashIndex), "ipRange");
        ParsedAddress end = parseLiteralAddress(ipRange.substring(dashIndex + 1), "ipRange");
        if (start.family != end.family) {
            throw new IllegalArgumentException("ipRange 起止地址的地址族不一致: " + ipRange);
        }
        if (start.value.compareTo(end.value) > 0) {
            throw new IllegalArgumentException("ipRange 起始地址大于结束地址: " + ipRange);
        }
        return new ParsedRange(start.family, start.value, end.value);
    }

    private static BigInteger toUnsignedBigInteger(byte[] bytes) {
        return new BigInteger(1, bytes);
    }

    /**
     * 解析点分十进制 IPv4 字面量为 4 字节数组。
     *
     * @throws IllegalArgumentException 当 {@code text} 不是合法的 IPv4 地址时抛出
     */
    private static byte[] parseIpv4(String text) {
        String[] parts = text.split("\\.", -1);
        if (parts.length != IPV4_BYTE_LENGTH) {
            throw new IllegalArgumentException("不是合法的 IPv4 地址: " + text);
        }
        byte[] bytes = new byte[IPV4_BYTE_LENGTH];
        for (int i = 0; i < IPV4_BYTE_LENGTH; i++) {
            String part = parts[i];
            if (part.isEmpty() || part.length() > 3 || !isAllDigits(part)) {
                throw new IllegalArgumentException("不是合法的 IPv4 地址: " + text);
            }
            int value = Integer.parseInt(part);
            if (value > 255) {
                throw new IllegalArgumentException("不是合法的 IPv4 地址: " + text);
            }
            bytes[i] = (byte) value;
        }
        return bytes;
    }

    private static boolean isAllDigits(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * 解析 IPv6 字面量为 16 字节数组，支持标准 8 组冒号分隔十六进制、{@code ::} 压缩零段（最多一次）、
     * 结尾内嵌 IPv4 形式（如 {@code ::ffff:192.168.1.1}）。
     *
     * @throws IllegalArgumentException 当 {@code text} 不是合法的 IPv6 地址时抛出
     */
    private static byte[] parseIpv6(String text) {
        int doubleColonIndex = text.indexOf("::");
        boolean hasDoubleColon = doubleColonIndex >= 0;
        String head = hasDoubleColon ? text.substring(0, doubleColonIndex) : text;
        String tail = hasDoubleColon ? text.substring(doubleColonIndex + 2) : "";
        if (hasDoubleColon && text.indexOf("::", doubleColonIndex + 1) >= 0) {
            throw new IllegalArgumentException("不是合法的 IPv6 地址（出现多个 \"::\"）: " + text);
        }

        List<byte[]> headGroups = parseIpv6Groups(head, text);
        List<byte[]> tailGroups = hasDoubleColon ? parseIpv6Groups(tail, text) : List.of();
        int totalGroups = headGroups.size() + tailGroups.size();
        if (!hasDoubleColon && totalGroups != IPV6_GROUP_COUNT) {
            throw new IllegalArgumentException("不是合法的 IPv6 地址（分组数量不对）: " + text);
        }
        if (hasDoubleColon && totalGroups >= IPV6_GROUP_COUNT) {
            throw new IllegalArgumentException("不是合法的 IPv6 地址（\"::\" 未压缩任何分组）: " + text);
        }

        byte[] result = new byte[IPV6_BYTE_LENGTH];
        int offset = 0;
        for (byte[] group : headGroups) {
            result[offset++] = group[0];
            result[offset++] = group[1];
        }
        offset = IPV6_BYTE_LENGTH - tailGroups.size() * 2;
        for (byte[] group : tailGroups) {
            result[offset++] = group[0];
            result[offset++] = group[1];
        }
        return result;
    }

    private static List<byte[]> parseIpv6Groups(String part, String original) {
        if (part.isEmpty()) {
            return List.of();
        }
        String[] tokens = part.split(":", -1);
        List<byte[]> groups = new ArrayList<>(tokens.length + 1);
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i];
            if (i == tokens.length - 1 && token.contains(".")) {
                byte[] embeddedIpv4 = parseIpv4(token);
                groups.add(new byte[] {embeddedIpv4[0], embeddedIpv4[1]});
                groups.add(new byte[] {embeddedIpv4[2], embeddedIpv4[3]});
                continue;
            }
            if (token.isEmpty() || token.length() > 4) {
                throw new IllegalArgumentException("不是合法的 IPv6 地址: " + original);
            }
            int value;
            try {
                value = Integer.parseInt(token, 16);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("不是合法的 IPv6 地址: " + original, exception);
            }
            groups.add(new byte[] {(byte) (value >> 8), (byte) value});
        }
        return groups;
    }
}
