package org.example.simple.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.example.simple.util.codec.RsaUtils;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link RandomUserInfoUtils} 的姓名/身份证号/手机号/地区/组合用户生成测试。 */
class RandomUserInfoUtilsTest {

    private static final Pattern PHONE_PATTERN = Pattern.compile("^1[3-9]\\d{9}$");
    private static final int AREA_CODE_TABLE_SIZE = 2842;

    /** 合法姓名：2 到 4 个汉字，不含空格、分隔符、数字或拉丁字母。 */
    private static final Pattern NAME_PATTERN = Pattern.compile("^[\\u4e00-\\u9fa5]{2,4}$");

    /** 仅由汉字组成（不限长度），用于校验内置数据池里的单字与姓名。 */
    private static final Pattern HAN_PATTERN = Pattern.compile("^[\\u4e00-\\u9fa5]+$");

    /** 姓名覆盖性与长度断言使用的样本量，足够大以使三类分支都稳定出现。 */
    private static final int NAME_SAMPLE_SIZE = 2000;

    @Test
    void testUserNames() {
        int count = 10;
        List<String> names = RandomUserInfoUtils.generateNames(count);
        List<String> idCardNos = RandomUserInfoUtils.generateIdCardNos(count);
        List<String> phoneNumbers = RandomUserInfoUtils.generatePhoneNumbers(count);
        List<String> regions = RandomUserInfoUtils.generateRegions(count);
        List<RandomUserInfo> userInfos = RandomUserInfoUtils.generateUsers(count);

        print(names);
        print(idCardNos);
        print(phoneNumbers);
        print(regions);
        print(userInfos);
    }

    void print(List<?> data) {
        data.forEach(System.out::println);
        System.out.println("====================");
    }

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
    void generateNames_allNamesAreTwoToFourChineseChars() {
        List<String> names = RandomUserInfoUtils.generateNames(NAME_SAMPLE_SIZE);
        for (String name : names) {
            assertTrue(NAME_PATTERN.matcher(name).matches(),
                    name + " 应当是 2 到 4 个汉字，且不含空格、分隔符、数字或拉丁字母");
        }
    }

    @Test
    void generateNames_coversHanAndMinorityNames() throws ReflectiveOperationException {
        Set<String> minorityNames = new HashSet<>(Arrays.asList(readStringArrayField("MINORITY_NAMES")));
        Set<String> singleSurnames = new HashSet<>(Arrays.asList(readStringArrayField("SURNAMES")));
        List<String> names = RandomUserInfoUtils.generateNames(NAME_SAMPLE_SIZE);

        boolean hasMinorityName = names.stream().anyMatch(minorityNames::contains);
        // 先排除少数民族池，避免 金顺姬/崔永哲 这类恰好以汉族单姓开头的民族姓名被误判为汉族姓名
        boolean hasHanName = names.stream()
                .anyMatch(name -> !minorityNames.contains(name) && singleSurnames.contains(name.substring(0, 1)));
        assertTrue(hasMinorityName, NAME_SAMPLE_SIZE + " 个样本中应当至少出现一个少数民族姓名");
        assertTrue(hasHanName, NAME_SAMPLE_SIZE + " 个样本中应当至少出现一个汉族单姓姓名");
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
    void generateUsers_allNamesAreTwoToFourChineseChars() {
        List<RandomUserInfo> users = RandomUserInfoUtils.generateUsers(500);
        for (RandomUserInfo user : users) {
            assertTrue(NAME_PATTERN.matcher(user.name()).matches(),
                    user.name() + " 应当是 2 到 4 个汉字，且不含空格、分隔符、数字或拉丁字母");
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

    @Test
    void builtInAreaCodeTableMatchesCsvSnapshot() {
        Map<String, String> areaCodeToRegion = loadAreaCodeToRegionMap();
        assertEquals(AREA_CODE_TABLE_SIZE, areaCodeToRegion.size(),
                "area-codes.csv 的条数应当等于内置地区码表声明的条数");

        // generateRegions 是无放回抽取，取满表大小即可拿到内置表的全部地区名称
        List<String> builtInRegions = RandomUserInfoUtils.generateRegions(AREA_CODE_TABLE_SIZE);
        assertEquals(AREA_CODE_TABLE_SIZE, builtInRegions.size());
        assertEquals(new HashSet<>(areaCodeToRegion.values()), new HashSet<>(builtInRegions),
                "内置地区码表的地区名称集合应当与 area-codes.csv 完全一致");
    }

    @Test
    void givenNameCharsHasTwoHundredDistinctChars() throws ReflectiveOperationException {
        String[] givenNameChars = readStringArrayField("GIVEN_NAME_CHARS");
        assertEquals(200, givenNameChars.length, "取名用字池应当有 200 个字");
        assertEquals(200, new HashSet<>(Arrays.asList(givenNameChars)).size(), "取名用字池不应出现重复字");
        for (String givenNameChar : givenNameChars) {
            assertEquals(1, givenNameChar.length(), givenNameChar + " 应当只有一个字");
            assertTrue(HAN_PATTERN.matcher(givenNameChar).matches(), givenNameChar + " 应当是汉字");
        }
    }

    @Test
    void compoundSurnamesAreDistinctTwoCharHanSurnames() throws ReflectiveOperationException {
        String[] compoundSurnames = readStringArrayField("COMPOUND_SURNAMES");
        assertEquals(compoundSurnames.length, new HashSet<>(Arrays.asList(compoundSurnames)).size(),
                "复姓池不应出现重复项");
        for (String compoundSurname : compoundSurnames) {
            assertEquals(2, compoundSurname.length(), compoundSurname + " 应当是 2 个字的复姓");
            assertTrue(HAN_PATTERN.matcher(compoundSurname).matches(), compoundSurname + " 应当只包含汉字");
        }
    }

    @Test
    void minorityNamesAreDistinctTwoToFourCharHanNames() throws ReflectiveOperationException {
        String[] minorityNames = readStringArrayField("MINORITY_NAMES");
        assertEquals(minorityNames.length, new HashSet<>(Arrays.asList(minorityNames)).size(),
                "少数民族姓名池不应出现重复项");
        for (String minorityName : minorityNames) {
            assertTrue(minorityName.length() >= 2 && minorityName.length() <= 4,
                    minorityName + " 的长度应当在 2 到 4 个字之间");
            assertTrue(HAN_PATTERN.matcher(minorityName).matches(),
                    minorityName + " 应当只包含汉字，不含 · 等分隔符");
        }
    }

    /**
     * 通过反射读取 {@link RandomUserInfoUtils} 的私有静态字符串数组常量。
     * <p>
     * 这些数据池不对外暴露（公开 API 只有 5 个生成方法），但"池本身的形状"——条数、是否去重、
     * 每条的长度与字符集——是公开行为无法完整覆盖的内部不变量，因此用反射直接校验，
     * 而不是为了测试把字段放宽到包可见。
     *
     * @param fieldName 字段名
     * @return 该字段的值
     */
    private String[] readStringArrayField(String fieldName) throws ReflectiveOperationException {
        Field field = RandomUserInfoUtils.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return (String[]) field.get(null);
    }

    private Map<String, String> loadAreaCodeToRegionMap() {
        Map<String, String> areaCodeToRegion = new HashMap<>();
        try (InputStream inputStream = RandomUserInfoUtilsTest.class.getResourceAsStream("/area-codes.csv")) {
            assertNotNull(inputStream, "测试参照文件 /area-codes.csv 应当存在于 classpath");
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
