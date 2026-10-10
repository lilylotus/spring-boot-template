package org.example.simple.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link RandomUserInfoUtils} 的姓名/身份证号/手机号/地区/组合用户生成测试。 */
class RandomUserInfoUtilsTest {

    private static final Pattern PHONE_PATTERN = Pattern.compile("^1[3-9]\\d{9}$");
    private static final int AREA_CODE_TABLE_SIZE = 2842;

    @Test
    void generateNames_returnsRequestedCountAndAllUnique() {
        List<String> names = RandomUserInfoUtils.generateNames(50);
        assertEquals(50, names.size());
        assertEquals(50, new HashSet<>(names).size());
    }

    @Test
    void generateNames_nonPositiveCount_throws() {
        assertThrows(IllegalArgumentException.class, () -> RandomUserInfoUtils.generateNames(0));
        assertThrows(IllegalArgumentException.class, () -> RandomUserInfoUtils.generateNames(-1));
    }

    @Test
    void generateIdCardNos_returnsRequestedCountAllUniqueAndValid() {
        List<String> idCardNos = RandomUserInfoUtils.generateIdCardNos(50);
        assertEquals(50, idCardNos.size());
        assertEquals(50, new HashSet<>(idCardNos).size());
        for (String idCardNo : idCardNos) {
            assertTrue(IdCardNoValidatorUtils.isValid(idCardNo), idCardNo + " 应当通过身份证号校验");
        }
    }

    @Test
    void generateIdCardNos_nonPositiveCount_throws() {
        assertThrows(IllegalArgumentException.class, () -> RandomUserInfoUtils.generateIdCardNos(0));
        assertThrows(IllegalArgumentException.class, () -> RandomUserInfoUtils.generateIdCardNos(-1));
    }

    @Test
    void generatePhoneNumbers_returnsRequestedCountAllUniqueAndMatchingFormat() {
        List<String> phoneNumbers = RandomUserInfoUtils.generatePhoneNumbers(50);
        assertEquals(50, phoneNumbers.size());
        assertEquals(50, new HashSet<>(phoneNumbers).size());
        for (String phoneNumber : phoneNumbers) {
            assertTrue(PHONE_PATTERN.matcher(phoneNumber).matches(), phoneNumber + " 应当匹配手机号格式");
        }
    }

    @Test
    void generatePhoneNumbers_nonPositiveCount_throws() {
        assertThrows(IllegalArgumentException.class, () -> RandomUserInfoUtils.generatePhoneNumbers(0));
        assertThrows(IllegalArgumentException.class, () -> RandomUserInfoUtils.generatePhoneNumbers(-1));
    }

    @Test
    void generateRegions_returnsRequestedCountAndAllUnique() {
        List<String> regions = RandomUserInfoUtils.generateRegions(50);
        assertEquals(50, regions.size());
        assertEquals(50, new HashSet<>(regions).size());
    }

    @Test
    void generateRegions_nonPositiveCount_throws() {
        assertThrows(IllegalArgumentException.class, () -> RandomUserInfoUtils.generateRegions(0));
        assertThrows(IllegalArgumentException.class, () -> RandomUserInfoUtils.generateRegions(-1));
    }

    @Test
    void generateRegions_countExceedsAreaCodeTableSize_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> RandomUserInfoUtils.generateRegions(AREA_CODE_TABLE_SIZE + 1));
    }

    @Test
    void generateRegions_countEqualsAreaCodeTableSize_returnsAllRegions() {
        List<String> regions = RandomUserInfoUtils.generateRegions(AREA_CODE_TABLE_SIZE);
        assertEquals(AREA_CODE_TABLE_SIZE, regions.size());
        assertEquals(AREA_CODE_TABLE_SIZE, new HashSet<>(regions).size());
    }

    @Test
    void generateUsers_returnsRequestedCount() {
        List<RandomUserInfo> users = RandomUserInfoUtils.generateUsers(50);
        assertEquals(50, users.size());
    }

    @Test
    void generateUsers_namesPhoneNumbersAndIdCardNosAreEachUnique() {
        List<RandomUserInfo> users = RandomUserInfoUtils.generateUsers(50);
        Set<String> names = new HashSet<>();
        Set<String> phoneNumbers = new HashSet<>();
        Set<String> idCardNos = new HashSet<>();
        for (RandomUserInfo user : users) {
            names.add(user.name());
            phoneNumbers.add(user.phoneNumber());
            idCardNos.add(user.idCardNo());
        }
        assertEquals(50, names.size());
        assertEquals(50, phoneNumbers.size());
        assertEquals(50, idCardNos.size());
    }

    @Test
    void generateUsers_idCardNosAreValidAndPhoneNumbersMatchFormat() {
        List<RandomUserInfo> users = RandomUserInfoUtils.generateUsers(50);
        for (RandomUserInfo user : users) {
            assertTrue(IdCardNoValidatorUtils.isValid(user.idCardNo()), user.idCardNo() + " 应当通过身份证号校验");
            assertTrue(PHONE_PATTERN.matcher(user.phoneNumber()).matches(),
                    user.phoneNumber() + " 应当匹配手机号格式");
        }
    }

    @Test
    void generateUsers_regionMatchesIdCardAreaCode() {
        Map<String, String> areaCodeToRegion = loadAreaCodeToRegionMap();
        List<RandomUserInfo> users = RandomUserInfoUtils.generateUsers(50);
        for (RandomUserInfo user : users) {
            String areaCode = user.idCardNo().substring(0, 6);
            assertEquals(areaCodeToRegion.get(areaCode), user.region(),
                    "idCardNo=" + user.idCardNo() + " 的 region 应当等于地区码表中 " + areaCode + " 对应的地区名称");
        }
    }

    @Test
    void generateUsers_nonPositiveCount_throws() {
        assertThrows(IllegalArgumentException.class, () -> RandomUserInfoUtils.generateUsers(0));
        assertThrows(IllegalArgumentException.class, () -> RandomUserInfoUtils.generateUsers(-1));
    }

    @Test
    void generateNames_largeCount_succeedsWithinReasonableTime() {
        List<String> names = RandomUserInfoUtils.generateNames(1000);
        assertEquals(1000, new HashSet<>(names).size());
    }

    @Test
    void generateIdCardNos_largeCount_succeedsWithinReasonableTime() {
        List<String> idCardNos = RandomUserInfoUtils.generateIdCardNos(1000);
        assertEquals(1000, new HashSet<>(idCardNos).size());
    }

    @Test
    void generatePhoneNumbers_largeCount_succeedsWithinReasonableTime() {
        List<String> phoneNumbers = RandomUserInfoUtils.generatePhoneNumbers(1000);
        assertEquals(1000, new HashSet<>(phoneNumbers).size());
    }

    @Test
    void generateUsers_largeCount_succeedsWithinReasonableTime() {
        List<RandomUserInfo> users = RandomUserInfoUtils.generateUsers(1000);
        assertEquals(1000, users.size());
    }

    private Map<String, String> loadAreaCodeToRegionMap() {
        Map<String, String> areaCodeToRegion = new HashMap<>();
        try (InputStream inputStream = RandomUserInfoUtilsTest.class.getResourceAsStream("/area-codes.csv")) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    int commaIndex = line.indexOf(',');
                    areaCodeToRegion.put(line.substring(0, commaIndex), line.substring(commaIndex + 1));
                }
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return areaCodeToRegion;
    }
}
