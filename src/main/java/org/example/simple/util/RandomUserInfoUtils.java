package org.example.simple.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 随机用户信息生成工具类：批量生成用于测试/演示的随机姓名、身份证号、手机号、行政区划地址，
 * 以及把它们组合成一条完整用户记录。
 * <p>
 * 提供 5 个相互独立的静态方法：
 * <ul>
 *   <li>{@link #generateNames(int)}：批次内姓名两两不重复；</li>
 *   <li>{@link #generateIdCardNos(int)}：批次内身份证号两两不重复，且每个都能被
 *       {@link IdCardNoValidatorUtils#isValid(String)} 校验通过；</li>
 *   <li>{@link #generatePhoneNumbers(int)}：批次内手机号两两不重复，格式为
 *       {@code ^1[3-9]\d{9}$} 且前缀取自内置的真实运营商号段清单；</li>
 *   <li>{@link #generateRegions(int)}：批次内行政区划地址两两不重复，从内置的官方地区码表
 *       （民政部 2023 年 1 月列表，县级粒度，共 {@link #AREA_CODES} 条）中无放回随机抽取；</li>
 *   <li>{@link #generateUsers(int)}：批量生成 {@link RandomUserInfo}，姓名/手机号/身份证号
 *       在本次返回结果内分别两两不重复（不要求三者联合唯一），每条记录的地区与该记录身份证号的
 *       地区码保持一致。</li>
 * </ul>
 * <p>
 * 这 5 个方法彼此独立：唯一性保证只作用于同一次方法调用返回的列表内部，不跨方法、
 * 不跨同一方法的不同调用——本类不维护任何跨调用的全局状态。
 * <p>
 * 地区码表是民政部 2023 年 1 月公布的《2023年中华人民共和国县以上行政区划代码》
 * （https://www.mca.gov.cn/mzsj/xzqh/2023/202301xzqh.html）中全部县级（区/县/县级市）叶子节点的
 * 静态快照，不会随后续行政区划调整自动更新。手机号号段前缀同样是静态内置清单，不做实时的
 * 携号转网/在网状态校验。两者都只保证"结构和号段/地区真实存在"，不保证生成的具体号码/是否仍是
 * 当前有效编码。
 * <p>
 * 本工具类无状态，所有方法均可被多线程并发调用。
 */
public final class RandomUserInfoUtils {

    private static final int MAX_ATTEMPTS_PER_ELEMENT = 1000;

    private static final LocalDate MIN_BIRTH_DATE = LocalDate.of(1960, 1, 1);
    private static final int MIN_AGE_YEARS = 18;

    /** 前 17 位各位的加权系数，顺序对应第 1 位到第 17 位，来自 GB 11643 附录 B，与 {@link IdCardNoValidatorUtils} 一致。 */
    private static final int[] CHECK_CODE_WEIGHTS = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};

    /** 加权求和对 11 取余后的校验码映射表，下标为余数，来自 GB 11643 附录 B。 */
    private static final char[] CHECK_CODES = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};

    private static final int CHECK_CODE_MODULUS = 11;

    /** 内置常见单字姓氏池。 */
    private static final String[] SURNAMES = {
            "李", "王", "张", "刘", "陈", "杨", "黄", "赵", "吴", "周",
            "徐", "孙", "马", "朱", "胡", "林", "郭", "何", "高", "罗",
            "郑", "梁", "谢", "宋", "唐", "许", "邓", "冯", "韩", "曹",
            "曾", "彭", "萧", "蔡", "潘", "田", "董", "袁", "于", "余",
            "叶", "蒋", "杜", "苏", "魏", "程", "吕", "丁", "沈", "任",
            "姚", "卢", "傅", "钟", "姜", "崔", "谭", "廖", "范", "汪",
            "陆", "金", "石", "戴", "贾", "韦", "夏", "邱", "方", "侯",
            "邹", "熊", "孟", "秦", "白", "江", "阎", "薛", "尹", "段",
            "雷", "黎", "史", "龙", "陶", "贺", "顾", "毛", "郝", "龚",
            "邵", "万", "钱", "严", "覃", "武", "戚", "莫", "孔", "向"
    };

    /** 内置常见取名用字池，用于拼出 1-2 个字的名。 */
    private static final String[] GIVEN_NAME_CHARS = {
            "伟", "芳", "娜", "敏", "静", "丽", "强", "磊", "军", "洋",
            "勇", "艳", "杰", "娟", "涛", "明", "超", "霞", "平", "刚",
            "玲", "华", "莉", "萍", "红", "玉", "林", "月", "慧", "鑫",
            "兰", "秀", "兴", "荣", "建", "国", "文", "辉", "力", "海",
            "山", "峰", "宇", "浩", "天", "云", "阳", "光", "亮", "星",
            "辰", "春", "夏", "秋", "东", "南", "西", "北", "中", "正",
            "义", "信", "诚", "良", "善", "美", "丹", "洁", "清", "雅",
            "琳", "瑶", "悦", "欣", "怡", "婷", "瑾", "萱", "梦", "琪",
            "佳", "嘉", "睿", "博", "宸", "轩", "铭", "瑞", "泽", "晨",
            "晓", "昊", "旭", "彬", "斌", "鹤", "龙", "虎", "鹏", "妍"
    };

    /** 内置手机号运营商号段前缀清单（3 位数字），覆盖中国移动/联通/电信及部分虚拟运营商已公开使用的号段。 */
    private static final String[] PHONE_PREFIXES = {
            "130", "131", "132", "133", "134", "135", "136", "137", "138", "139",
            "145", "146", "147", "148", "149", "150", "151", "152", "153", "155",
            "156", "157", "158", "159", "162", "165", "166", "167", "170", "171",
            "172", "173", "175", "176", "177", "178", "180", "181", "182", "183",
            "184", "185", "186", "187", "188", "189", "191", "192", "193", "195",
            "196", "197", "198", "199"
    };

    /** 内置官方地区码表（民政部 2023 年 1 月列表的全部县级叶子节点），类加载时从 classpath 资源解析一次。 */
    private static final List<AreaCode> AREA_CODES = loadAreaCodes();

    private RandomUserInfoUtils() {
    }

    /**
     * 生成 {@code count} 个两两不重复的随机姓名。
     *
     * @param count 要生成的数量，必须是正整数
     * @return 大小为 {@code count} 的姓名列表，列表内部两两不重复
     * @throws IllegalArgumentException 当 {@code count} 不是正整数时抛出
     * @throws IllegalStateException 当在限定尝试次数内无法生成足够的唯一姓名时抛出
     */
    public static List<String> generateNames(int count) {
        requirePositive(count);
        Set<String> used = new HashSet<>();
        List<String> names = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            names.add(generateUniqueName(used));
        }
        return names;
    }

    /**
     * 生成 {@code count} 个两两不重复、且均能通过 {@link IdCardNoValidatorUtils#isValid(String)} 校验的 18 位身份证号。
     *
     * @param count 要生成的数量，必须是正整数
     * @return 大小为 {@code count} 的身份证号列表，列表内部两两不重复
     * @throws IllegalArgumentException 当 {@code count} 不是正整数时抛出
     * @throws IllegalStateException 当在限定尝试次数内无法生成足够的唯一身份证号时抛出
     */
    public static List<String> generateIdCardNos(int count) {
        requirePositive(count);
        Set<String> used = new HashSet<>();
        List<String> idCardNos = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            idCardNos.add(generateUniqueIdCardWithRegion(used).idCardNo());
        }
        return idCardNos;
    }

    /**
     * 生成 {@code count} 个两两不重复、匹配 {@code ^1[3-9]\d{9}$} 且前缀取自内置真实运营商号段清单的手机号。
     *
     * @param count 要生成的数量，必须是正整数
     * @return 大小为 {@code count} 的手机号列表，列表内部两两不重复
     * @throws IllegalArgumentException 当 {@code count} 不是正整数时抛出
     * @throws IllegalStateException 当在限定尝试次数内无法生成足够的唯一手机号时抛出
     */
    public static List<String> generatePhoneNumbers(int count) {
        requirePositive(count);
        Set<String> used = new HashSet<>();
        List<String> phoneNumbers = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            phoneNumbers.add(generateUniquePhoneNumber(used));
        }
        return phoneNumbers;
    }

    /**
     * 从内置官方地区码表中无放回随机选取 {@code count} 个两两不重复的地区名称。
     *
     * @param count 要生成的数量，必须是正整数且不超过内置地区码表的总条数
     * @return 大小为 {@code count} 的地区名称列表，列表内部两两不重复
     * @throws IllegalArgumentException 当 {@code count} 不是正整数，或超过内置地区码表总条数时抛出
     */
    public static List<String> generateRegions(int count) {
        requirePositive(count);
        if (count > AREA_CODES.size()) {
            throw new IllegalArgumentException(
                    "count 超过内置地区码表总条数: " + count + " > " + AREA_CODES.size());
        }
        List<AreaCode> shuffled = new ArrayList<>(AREA_CODES);
        Collections.shuffle(shuffled, ThreadLocalRandom.current());
        List<String> regions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            regions.add(shuffled.get(i).region());
        }
        return regions;
    }

    /**
     * 生成 {@code count} 条组合用户数据。姓名、手机号、身份证号在本次返回结果内分别两两不重复
     * （不要求三者联合唯一），每条记录的 {@code region} 与该记录 {@code idCardNo} 的地区码保持一致，
     * 但不要求不同记录之间地区互不相同。
     *
     * @param count 要生成的数量，必须是正整数
     * @return 大小为 {@code count} 的 {@link RandomUserInfo} 列表
     * @throws IllegalArgumentException 当 {@code count} 不是正整数时抛出
     * @throws IllegalStateException 当在限定尝试次数内无法生成足够的唯一姓名/身份证号/手机号时抛出
     */
    public static List<RandomUserInfo> generateUsers(int count) {
        requirePositive(count);
        Set<String> usedNames = new HashSet<>();
        Set<String> usedIdCardNos = new HashSet<>();
        Set<String> usedPhoneNumbers = new HashSet<>();
        List<RandomUserInfo> users = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String name = generateUniqueName(usedNames);
            IdCardWithRegion idCardWithRegion = generateUniqueIdCardWithRegion(usedIdCardNos);
            String phoneNumber = generateUniquePhoneNumber(usedPhoneNumbers);
            users.add(new RandomUserInfo(name, phoneNumber, idCardWithRegion.idCardNo(), idCardWithRegion.region()));
        }
        return users;
    }

    private static void requirePositive(int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("count 必须是正整数: " + count);
        }
    }

    private static String generateUniqueName(Set<String> used) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS_PER_ELEMENT; attempt++) {
            String name = buildRandomName();
            if (used.add(name)) {
                return name;
            }
        }
        throw new IllegalStateException(
                "在 " + MAX_ATTEMPTS_PER_ELEMENT + " 次尝试内无法生成新的唯一姓名，已生成 " + used.size() + " 个");
    }

    private static String buildRandomName() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        StringBuilder name = new StringBuilder();
        name.append(SURNAMES[random.nextInt(SURNAMES.length)]);
        int givenNameLength = random.nextBoolean() ? 1 : 2;
        for (int i = 0; i < givenNameLength; i++) {
            name.append(GIVEN_NAME_CHARS[random.nextInt(GIVEN_NAME_CHARS.length)]);
        }
        return name.toString();
    }

    private static IdCardWithRegion generateUniqueIdCardWithRegion(Set<String> used) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS_PER_ELEMENT; attempt++) {
            IdCardWithRegion candidate = buildRandomIdCardWithRegion();
            if (used.add(candidate.idCardNo())) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "在 " + MAX_ATTEMPTS_PER_ELEMENT + " 次尝试内无法生成新的唯一身份证号，已生成 " + used.size() + " 个");
    }

    private static IdCardWithRegion buildRandomIdCardWithRegion() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        AreaCode areaCode = AREA_CODES.get(random.nextInt(AREA_CODES.size()));
        LocalDate birthDate = randomBirthDate(random);
        String sequence = String.format("%03d", random.nextInt(1000));
        String first17 = areaCode.code() + birthDate.getYear()
                + String.format("%02d", birthDate.getMonthValue())
                + String.format("%02d", birthDate.getDayOfMonth())
                + sequence;
        String idCardNo = first17 + checkCode(first17);
        if (!IdCardNoValidatorUtils.isValid(idCardNo)) {
            throw new IllegalStateException("生成的身份证号未通过 IdCardNoValidatorUtils.isValid 自校验: " + idCardNo);
        }
        return new IdCardWithRegion(idCardNo, areaCode.region());
    }

    private static LocalDate randomBirthDate(ThreadLocalRandom random) {
        LocalDate maxBirthDate = LocalDate.now().minusYears(MIN_AGE_YEARS);
        long minEpochDay = MIN_BIRTH_DATE.toEpochDay();
        long maxEpochDay = maxBirthDate.toEpochDay();
        long randomEpochDay = minEpochDay + random.nextLong(maxEpochDay - minEpochDay + 1);
        return LocalDate.ofEpochDay(randomEpochDay);
    }

    private static char checkCode(String first17) {
        int sum = 0;
        for (int i = 0; i < CHECK_CODE_WEIGHTS.length; i++) {
            sum += (first17.charAt(i) - '0') * CHECK_CODE_WEIGHTS[i];
        }
        return CHECK_CODES[sum % CHECK_CODE_MODULUS];
    }

    private static String generateUniquePhoneNumber(Set<String> used) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS_PER_ELEMENT; attempt++) {
            String phoneNumber = buildRandomPhoneNumber();
            if (used.add(phoneNumber)) {
                return phoneNumber;
            }
        }
        throw new IllegalStateException(
                "在 " + MAX_ATTEMPTS_PER_ELEMENT + " 次尝试内无法生成新的唯一手机号，已生成 " + used.size() + " 个");
    }

    private static String buildRandomPhoneNumber() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        String prefix = PHONE_PREFIXES[random.nextInt(PHONE_PREFIXES.length)];
        return prefix + String.format("%08d", random.nextInt(100_000_000));
    }

    private static List<AreaCode> loadAreaCodes() {
        List<AreaCode> areaCodes = new ArrayList<>();
        try (InputStream inputStream = RandomUserInfoUtils.class.getResourceAsStream("/area-codes.csv")) {
            if (inputStream == null) {
                throw new IllegalStateException("classpath 资源 /area-codes.csv 不存在");
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    int commaIndex = line.indexOf(',');
                    areaCodes.add(new AreaCode(line.substring(0, commaIndex), line.substring(commaIndex + 1)));
                }
            }
        } catch (IOException exception) {
            throw new UncheckedIOException("加载 classpath 资源 /area-codes.csv 失败", exception);
        }
        return List.copyOf(areaCodes);
    }

    private record AreaCode(String code, String region) {
    }

    private record IdCardWithRegion(String idCardNo, String region) {
    }
}
