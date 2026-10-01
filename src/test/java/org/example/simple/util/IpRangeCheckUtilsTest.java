package org.example.simple.util;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link IpRangeCheckUtils} 的 IPv4/IPv6 CIDR、起止地址、单 IP 匹配与参数校验测试。 */
class IpRangeCheckUtilsTest {

    @Test
    void isInRange_ipv4Cidr_insideRange_returnsTrue() {
        assertTrue(IpRangeCheckUtils.isInRange("192.168.1.100", "192.168.1.0/24"));
    }

    @Test
    void isInRange_ipv4Cidr_networkAddressBoundary_returnsTrue() {
        assertTrue(IpRangeCheckUtils.isInRange("192.168.1.0", "192.168.1.0/24"));
    }

    @Test
    void isInRange_ipv4Cidr_broadcastAddressBoundary_returnsTrue() {
        assertTrue(IpRangeCheckUtils.isInRange("192.168.1.255", "192.168.1.0/24"));
    }

    @Test
    void isInRange_ipv4Cidr_outsideRange_returnsFalse() {
        assertFalse(IpRangeCheckUtils.isInRange("192.168.2.1", "192.168.1.0/24"));
    }

    @Test
    void isInRange_ipv4Cidr_slash32_onlyExactAddressMatches() {
        assertTrue(IpRangeCheckUtils.isInRange("10.0.0.1", "10.0.0.1/32"));
        assertFalse(IpRangeCheckUtils.isInRange("10.0.0.2", "10.0.0.1/32"));
    }

    @Test
    void isInRange_ipv4Cidr_slash0_matchesEverything() {
        assertTrue(IpRangeCheckUtils.isInRange("1.2.3.4", "0.0.0.0/0"));
        assertTrue(IpRangeCheckUtils.isInRange("255.255.255.255", "0.0.0.0/0"));
    }

    @Test
    void isInRange_ipv4ExplicitRange_insideRange_returnsTrue() {
        assertTrue(IpRangeCheckUtils.isInRange("10.0.0.50", "10.0.0.1-10.0.0.100"));
    }

    @Test
    void isInRange_ipv4ExplicitRange_boundaries_returnTrue() {
        assertTrue(IpRangeCheckUtils.isInRange("10.0.0.1", "10.0.0.1-10.0.0.100"));
        assertTrue(IpRangeCheckUtils.isInRange("10.0.0.100", "10.0.0.1-10.0.0.100"));
    }

    @Test
    void isInRange_ipv4ExplicitRange_outsideRange_returnsFalse() {
        assertFalse(IpRangeCheckUtils.isInRange("10.0.0.200", "10.0.0.1-10.0.0.100"));
    }

    @Test
    void isInRange_singleIpv4_exactMatch_returnsTrue() {
        assertTrue(IpRangeCheckUtils.isInRange("192.168.1.1", "192.168.1.1"));
    }

    @Test
    void isInRange_singleIpv4_mismatch_returnsFalse() {
        assertFalse(IpRangeCheckUtils.isInRange("192.168.1.2", "192.168.1.1"));
    }

    @Test
    void isInRange_ipv6Cidr_fullForm_insideRange_returnsTrue() {
        assertTrue(IpRangeCheckUtils.isInRange("2001:0db8:0000:0000:0000:0000:0000:0001", "2001:db8::/32"));
    }

    @Test
    void isInRange_ipv6Cidr_compressedForm_insideRange_returnsTrue() {
        assertTrue(IpRangeCheckUtils.isInRange("2001:db8::1", "2001:db8::/32"));
    }

    @Test
    void isInRange_ipv6Cidr_compressedForm_outsideRange_returnsFalse() {
        assertFalse(IpRangeCheckUtils.isInRange("2001:db9::1", "2001:db8::/32"));
    }

    @Test
    void isInRange_ipv6Cidr_embeddedIpv4Form_insideRange_returnsTrue() {
        assertTrue(IpRangeCheckUtils.isInRange("::ffff:192.168.1.100", "::ffff:192.168.1.0/120"));
    }

    @Test
    void isInRange_ipv6Cidr_slash128_onlyExactAddressMatches() {
        assertTrue(IpRangeCheckUtils.isInRange("::1", "::1/128"));
        assertFalse(IpRangeCheckUtils.isInRange("::2", "::1/128"));
    }

    @Test
    void isInRange_ipv6Cidr_slash0_matchesEverything() {
        assertTrue(IpRangeCheckUtils.isInRange("::1", "::/0"));
        assertTrue(IpRangeCheckUtils.isInRange("ffff::1", "::/0"));
    }

    @Test
    void isInRange_ipv6ExplicitRange_insideRange_returnsTrue() {
        assertTrue(IpRangeCheckUtils.isInRange("2001:db8::50", "2001:db8::1-2001:db8::100"));
    }

    @Test
    void isInRange_ipv6ExplicitRange_outsideRange_returnsFalse() {
        assertFalse(IpRangeCheckUtils.isInRange("2001:db8::200", "2001:db8::1-2001:db8::100"));
    }

    @Test
    void isInRange_singleIpv6_exactMatch_returnsTrue() {
        assertTrue(IpRangeCheckUtils.isInRange("::1", "::1"));
    }

    @Test
    void isInRange_mismatchedFamily_ipv6AgainstIpv4Cidr_returnsFalse() {
        assertFalse(IpRangeCheckUtils.isInRange("2001:db8::1", "192.168.1.0/24"));
    }

    @Test
    void isInRange_mismatchedFamily_ipv4AgainstIpv6Cidr_returnsFalse() {
        assertFalse(IpRangeCheckUtils.isInRange("192.168.1.1", "2001:db8::/32"));
    }

    @Test
    void isInRange_withNullIp_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> IpRangeCheckUtils.isInRange(null, "192.168.1.0/24"));
    }

    @Test
    void isInRange_withBlankIp_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> IpRangeCheckUtils.isInRange("  ", "192.168.1.0/24"));
    }

    @Test
    void isInRange_withInvalidIpv4Octet_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> IpRangeCheckUtils.isInRange("999.1.1.1", "192.168.1.0/24"));
    }

    @Test
    void isInRange_withHostname_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> IpRangeCheckUtils.isInRange("not-an-ip", "192.168.1.0/24"));
    }

    @Test
    void isInRange_withNullIpRange_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> IpRangeCheckUtils.isInRange("192.168.1.1", null));
    }

    @Test
    void isInRange_withBlankIpRange_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> IpRangeCheckUtils.isInRange("192.168.1.1", "  "));
    }

    @Test
    void isInRange_withNegativeCidrPrefix_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> IpRangeCheckUtils.isInRange("192.168.1.1", "192.168.1.0/-1"));
    }

    @Test
    void isInRange_withCidrPrefixExceedingIpv4Limit_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> IpRangeCheckUtils.isInRange("192.168.1.1", "192.168.1.0/33"));
    }

    @Test
    void isInRange_withCidrPrefixExceedingIpv6Limit_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> IpRangeCheckUtils.isInRange("::1", "::/129"));
    }

    @Test
    void isInRange_withStartGreaterThanEnd_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> IpRangeCheckUtils.isInRange("10.0.0.1", "10.0.0.100-10.0.0.1"));
    }

    @Test
    void isInRange_withMismatchedFamilyExplicitRange_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> IpRangeCheckUtils.isInRange("10.0.0.1", "10.0.0.1-::1"));
    }

    @Test
    void isInAnyRange_matchingOneOfManyRanges_returnsTrue() {
        List<String> ranges = Arrays.asList("10.0.0.0/8", "172.16.0.0/12", "192.168.1.0/24");

        assertTrue(IpRangeCheckUtils.isInAnyRange("192.168.1.50", ranges));
    }

    @Test
    void isInAnyRange_matchingNone_returnsFalse() {
        List<String> ranges = Arrays.asList("10.0.0.0/8", "172.16.0.0/12");

        assertFalse(IpRangeCheckUtils.isInAnyRange("8.8.8.8", ranges));
    }

    @Test
    void isInAnyRange_emptyCollection_returnsFalse() {
        assertFalse(IpRangeCheckUtils.isInAnyRange("192.168.1.1", List.of()));
    }

    @Test
    void isInAnyRange_withNullIp_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> IpRangeCheckUtils.isInAnyRange(null, List.of("192.168.1.0/24")));
    }

    @Test
    void isInAnyRange_withNullCollection_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class, () -> IpRangeCheckUtils.isInAnyRange("192.168.1.1", null));
    }

    @Test
    void isInAnyRange_withNullElementInCollection_throwsIllegalArgumentException() {
        List<String> ranges = Arrays.asList("192.168.1.0/24", null);

        assertThrows(IllegalArgumentException.class, () -> IpRangeCheckUtils.isInAnyRange("192.168.1.1", ranges));
    }

    @Test
    void isInAnyRange_withInvalidElementInCollection_throwsIllegalArgumentException() {
        List<String> ranges = Arrays.asList("192.168.1.0/24", "not-a-range");

        assertThrows(IllegalArgumentException.class, () -> IpRangeCheckUtils.isInAnyRange("8.8.8.8", ranges));
    }
}
