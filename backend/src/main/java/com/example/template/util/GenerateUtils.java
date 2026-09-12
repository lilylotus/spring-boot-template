package com.example.template.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class GenerateUtils {

    /**
     * 18位 = 6位地区码 + 8位生日码 + 3位顺序码 + 1位校验码
     * 地区码	1-6	    地址编码，对应省市区（基于最新行政区划代码）。
     * 生日码	7-14	出生日期（YYYYMMDD格式），年份用4位。
     * 顺序码	15-17	同地区同生日人员的顺序号： 奇数=男性，偶数=女性； 范围：001-999。
     * 校验码	18	    通过前17位计算得出，防止输入错误（可能为0-9或X）。	X（校验码为10时用X代替）
     * <p>
     * 重庆行政区划：<a href="https://www.mca.gov.cn/mzsj/xzqh/2023/202301xzqh.html">...</a>
     * 统计用区划代码和城乡划分代码编制规则: <a href="https://www.stats.gov.cn/sj/tjbz/gjtjbz/202302/t20230213_1902741.html">...</a>
     */
    static final String[] ADMINISTRATIVE_DIVISION = {"500000", "500101", "500102", "500103", "500104", "500105", "500106",
        "500107", "500108", "500109", "500110", "500111", "500112", "500113", "500114", "500115", "500116", "500117",
        "500118", "500119", "500120", "500151", "500152", "500153", "500154", "500155", "500156", "500229", "500230",
        "500231", "500233", "500235", "500236", "500237", "500238", "500240", "500241", "500242", "500243",
        "110000", "110101", "110102", "110105", "110106", "110107", "110108", "110109", "110111", "110112", "110113",
        "110114", "110115", "110116", "110117", "110118", "110119", "310000", "310101", "310104", "310105", "310106",
        "310107", "310109", "310110", "310112", "310113", "310114", "310115", "310116", "310117", "310118", "310120", "310151"};

    static final DateTimeFormatter DTF = DateTimeFormatter.ofPattern("yyyyMMdd");

    static final int LIVE_YEAR = 100;
    static final int LIVE_MONTH = 12;
    static final int LIVE_DAY = 31;
    static final int ORDER_CODE = 999;

    // 权重系数表
    private static final int[] WEIGHTS = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
    private static final String CHECK_CODES = "10X98765432";

    private static boolean validate(String id) {
        if (id == null || id.length() != 18) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            sum += (id.charAt(i) - '0') * WEIGHTS[i];
        }
        char expectedCheckCode = CHECK_CODES.charAt(sum % 11);
        return id.charAt(17) == expectedCheckCode;
    }

    /**
     * true - female , false - male
     */
    public static boolean parseGender(String idCard) {
        String genderTag = idCard.substring(14, 17);
        //System.out.println(genderTag);
        return ((Integer.parseInt(genderTag) % 2) == 0);
    }

    public static Map<String, String> generateRegionDataFormat() {
        Map<String, String> regionData = new LinkedHashMap<>(1024);
        Map<String, String> formatRegionData = new LinkedHashMap<>(1024);
        Map<String, List<String>> rootRegionData = new LinkedHashMap<>(64);

        List<String> lines;
        try {
            lines = Files.readAllLines(Path.of(Objects.requireNonNull(GenerateUtils.class.getResource("/other/region-data.txt")).toURI()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return Collections.emptyMap();
        }
        lines.forEach(line -> {
            String[] split = line.split(" +");
            regionData.put(split[0], split[1]);
        });
        regionData.forEach((k, v) -> {
            String provincial = k.substring(0, 2);
            if (k.endsWith("0000")) {
                rootRegionData.put(provincial, new ArrayList<>(64));
                formatRegionData.put(k, v);
            } else if (rootRegionData.containsKey(provincial)) {
                rootRegionData.get(provincial).add(k);
                formatRegionData.put(k, regionData.get(provincial + "0000") + v);
            }
        });
        return formatRegionData;
    }

    public static List<String> generateIdCard(int count) {
        Set<String> idSet = new HashSet<>(count);
        // 18位 = 6位地区码 + 8位生日码 + 3位顺序码 + 1位校验码
        for (; idSet.size() < count; ) {
            Random random = new Random(System.nanoTime());
            String ad = ADMINISTRATIVE_DIVISION[random.nextInt(ADMINISTRATIVE_DIVISION.length)];
            String birthday = LocalDate.now()
                .minusYears(random.nextInt(LIVE_YEAR))
                .minusMonths(random.nextInt(LIVE_MONTH))
                .minusDays(random.nextInt(LIVE_DAY)).format(DTF);
            int orderCode = random.nextInt(ORDER_CODE) + 1;
            String idCard = ad + birthday + String.format("%03d", orderCode);
            // 校验码 = (12 - (S % 11)) % 11 : 结果为10时用X代替，否则直接写数字。
            int sum = 0;
            for (int j = 0; j < WEIGHTS.length; j++) {
                sum += (WEIGHTS[j] * (idCard.charAt(j) - '0'));
            }
            int checksum = (12 - (sum % 11)) % 11;
            idCard = idCard + (checksum == 10 ? "X" : Integer.toString(checksum));
            if (validate(idCard)) {
                idSet.add(idCard);
            } else {
                System.out.println("validate false [" + idCard + "]");
                throw new IllegalArgumentException("validate false [" + idCard + "]");
            }
        }
        return new ArrayList<>(idSet);
    }

    /**
     * 移动：134(0-8)、135、136、137、138、139、150、151、152、157、158、159、178、182、183、184、187、188、195、198
     * 147（物联网）、148（公众移动通信）、172（物联网）
     * 195/198 为 5G 号段
     */
    static final String[] MOBILE_PREFIX = {"134", "135", "136", "137", "138", "139",
        "147", "148",
        "150", "151", "152",
        "157", "158", "159",
        "172", "178",
        "182", "183", "184",
        "187", "188",
        "195", "198"};

    /**
     * 联通：130~132、145~146、155~156、166~167、175~176、185~186、196
     * 196 为 5G 号段
     */
    static final String[] UNICOM_PREFIX = {"130", "131", "132",
        "145", "146",
        "155", "156",
        "166", "167",
        "175", "176",
        "185", "186",
        "196"};

    /**
     * 电信：133、149、153、173、177、180~181、189、191、193、199
     */
    static final String[] TELECOM_PREFIX = {"133", "149", "153", "173", "177",
        "180", "181", "189", "191", "193", "199"};

    /**
     * 虚拟运营商：1703、1705、1706、165（移动合作）
     * 1704、1707、1708、1709、171、167（联通合作）
     * 1700、1701、1702、162、1740（电信合作）
     */
    static final String[] VIRTUAL_PREFIX = {"1703", "1705", "1706", "165", "1704", "1707", "1708", "1709", "171",
        "167", "1700", "1701", "1702", "162", "1740"};

    static final String[] NUMBER_ARRAY = {"0", "1", "2", "3", "4", "5", "6", "7", "8", "9"};

    public static List<String> generatePhone(int count) {
        // [号段][地区分配][用户号]
        Set<String> phoneSet = new HashSet<>(count);
        int prefixLen = MOBILE_PREFIX.length + UNICOM_PREFIX.length + TELECOM_PREFIX.length;
        int distLen = 4;
        int userLen = 4;
        String[] prefixArray = new String[prefixLen];
        System.arraycopy(MOBILE_PREFIX, 0, prefixArray, 0, MOBILE_PREFIX.length);
        System.arraycopy(UNICOM_PREFIX, 0, prefixArray, MOBILE_PREFIX.length, UNICOM_PREFIX.length);
        System.arraycopy(TELECOM_PREFIX, 0, prefixArray, MOBILE_PREFIX.length + UNICOM_PREFIX.length, TELECOM_PREFIX.length);

        while (phoneSet.size() < count) {
            Random random = new Random(System.nanoTime());
            String phone = prefixArray[random.nextInt(prefixLen)] +
                generateNumberString(distLen) +
                generateNumberString(userLen);
            phoneSet.add(phone);
        }

        return new ArrayList<>(phoneSet);
    }

    private static String generateNumberString(int count) {
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            Random random = new Random(System.nanoTime());
            sb.append(NUMBER_ARRAY[random.nextInt(NUMBER_ARRAY.length)]);
        }
        return sb.toString();
    }

    /**
     * 前 100 姓氏
     */
    static final String[] SURNAME = {"李", "王", "张", "刘", "陈", "杨", "赵", "黄", "周", "吴",
        "徐", "孙", "胡", "朱", "高", "林", "何", "郭", "马", "罗",
        "梁", "宋", "郑", "谢", "韩", "唐", "冯", "于", "董", "萧",
        "程", "曹", "袁", "邓", "许", "傅", "沈", "曾", "彭", "吕",
        "苏", "卢", "蒋", "蔡", "贾", "丁", "魏", "薛", "叶", "阎",
        "余", "潘", "杜", "戴", "夏", "钟", "汪", "田", "任", "姜",
        "范", "方", "石", "姚", "谭", "廖", "邹", "熊", "金", "陆",
        "郝", "孔", "白", "崔", "康", "毛", "邱", "秦", "江", "史",
        "顾", "侯", "邵", "孟", "龙", "万", "段", "漕", "钱", "汤",
        "尹", "黎", "易", "常", "武", "乔", "贺", "赖", "龚", "文"};

    static final String[] COMPOUND_SURNAME = {"欧阳", "太史", "端木", "上官", "司马", "东方", "独孤", "南宫", "万俟", "闻人",
        "夏侯", "诸葛", "尉迟", "公羊", "赫连", "澹台", "皇甫", "宗政", "濮阳", "公冶",
        "太叔", "申屠", "公孙", "慕容", "仲孙", "钟离", "长孙", "宇文", "司徒", "鲜于",
        "司空", "闾丘", "子车", "亓官", "司寇", "巫马", "公西", "颛孙", "壤驷", "公良",
        "漆雕", "乐正", "宰父", "谷梁", "拓跋", "夹谷", "轩辕", "令狐", "段干", "百里",
        "呼延", "东郭", "南门", "羊舌", "微生", "公户", "公玉", "公仪", "梁丘", "公仲",
        "公上", "公门", "公山", "公坚", "左丘", "公伯", "西门", "公祖", "第五", "公乘",
        "贯丘", "公晳", "南荣", "东里", "东宫", "仲长", "子书", "子桑", "即墨", "达奚",
        "褚师", "吴铭"};

    static final String[] MALE_NAME = {"伟", "强", "磊", "浩", "宇", "杰", "勇", "峰", "斌", "阳",
        "睿", "哲", "超", "翔", "辰", "昊", "铮", "翰", "峻", "川",
        "云舟", "景行", "墨白", "清晏", "怀瑾"};

    static final String[] FEMALE_NAME = {"娜", "敏", "静", "婷", "丽", "芳", "怡", "颖", "雯", "雪",
        "琳", "瑶", "莹", "蕾", "洁", "彤", "雨", "欣", "玥", "芷",
        "疏影", "青梧", "婉清", "知夏", "南乔"};

    static final String[] COMMON_NAME = {"民信", "登明", "骁克", "晟忱", "歆琪", "嘉盼", "俭倩", "瑗若", "子盼", "炎泉", "沛悦", "朵霁", "渊北", "骊林", "福笛", "镇牧", "将垚", "珏睿", "荔喻", "隽俐", "谦和", "玮淳", "琳岭", "怡海", "斐椒", "怡晴", "菁轶", "韵玉", "坦原", "荟维", "仪鹃", "宣凌", "鼎总", "礼俊", "珩孝", "然华", "曦珠", "飚涌", "韵子", "尚勤", "申贝", "苏沙", "莺琚", "欣方", "根里", "彬妹", "君金", "沙楚", "义璐", "宙燃", "岳余", "芝畅", "蕾菊", "伊达", "岗皓", "榕勇", "连盈", "坤北", "旻冕", "望一", "杨伶", "争翊", "迅朝", "遥骏", "震镇", "素荷", "卓贝", "雄勉", "驰佳", "咪眉", "蜜忻", "霖冰", "颜立", "习宁", "曙霄", "苹琪", "鉴宽", "阳轶", "欣嘉", "鸿昆", "焘垚", "音菁", "超椒", "泓姗", "甜钰", "玮彩", "旖春", "滨丰", "璇丛", "艺逸", "展黎", "乐允", "蕴葵", "萌嫱", "凡纯", "韶弘", "方松", "启旺", "蓉微", "琰霆", "珩瑾", "韵鹭", "红波", "昆禄", "滔飞", "发灵", "煦振", "靖松", "吟焕", "红熙", "丽杏", "鹤衡", "平余", "月杰", "默丹", "宜慧", "松毓", "安柱", "灵勇", "同唯", "知芊", "言郁", "风州", "鹃愉", "菡书", "幸熙", "慧亚", "津媚", "予露", "妮多", "恋漪", "耘默", "深煊", "达群", "钊河", "辰钧", "娜鹭", "恋真", "洋炼", "雷纲", "剑颂", "椒妹", "颖琥", "鸣皓", "绮琚", "君攀", "洋劫", "骏宙", "育威", "励津", "筝连", "萱沁", "思怡", "淳天", "津艳", "星韵", "琛兵", "源轲", "培艺", "颂典", "融实", "洁艺", "琦富", "为民", "骏韦", "荷萌", "淮飙", "晶旖", "庄能", "钊晗", "洁静", "俪耘", "娆雪", "沙伦", "千绚", "格芹", "筝静", "山薇", "果希", "龙斐", "子添", "日炎", "顺赢", "纯蓓", "蒙玫", "将全", "英滔", "婵影", "萍令", "会娇", "蕙筱", "骊钥", "壮锋", "溢钧", "香莹", "蓉静", "懿胡", "岩锦", "溢景", "婉良", "琥连", "培宣", "洵榕", "栋朝", "昀习", "京静", "彬洵", "芮娆", "瑾娅", "朔秋", "茹佩", "珠凌", "莉凤", "尚丛", "彬媚", "想励", "雪伊", "宜能", "妃娣", "庭稳", "蕴果", "盛怀", "馨婧", "竹蕴", "玥芸", "巧炯", "振朵", "星思", "侃唯", "开亮", "京臻", "攀臻", "卉展", "举创", "媛盈", "朝卓", "嫚泳", "习虹", "旎伊", "嫚展", "俭伊", "翌罡", "亚若", "奕笛", "义舟", "征广", "革朗", "闻逸", "迅思", "忱多", "克众", "蔷晔", "书乐", "迅榕", "晏恩", "宽坦", "杨彬", "飙启", "崇灵", "丰飙", "羽根", "葵一", "升原", "灿保", "宝焰", "骊霓", "励姬", "莺榕", "联富", "欣婉", "晴芮", "唯霞", "惟展", "飞宇", "旭希", "樱蕊", "领尧", "劲路", "印默", "州耘", "榕嵩", "想菲", "尤莺", "遥展", "岚音", "严霖", "榕珂", "南滔", "魁峥", "晓雯", "婕桢", "瑜俪", "菊昀", "昀财", "博里", "凌葵", "婵欣", "丹芬", "想珍", "青韶", "胤疆", "婧音", "焕洁", "娆蕙", "郁品", "展妍", "婵幸", "娓妙", "喜雄", "妤彤", "臣延", "潮浩", "婕桃", "滨纯", "皎恬", "崴飙", "淼昂", "苹允", "望沁", "珏宝", "达瑛", "寒仪", "麟洲", "连飞", "廷雍", "秋童", "晟秀", "容爱", "愉茉", "涌准", "秋曙", "朗正", "宇爽", "琦鹰", "定源", "重雍", "盼骅", "喻恋", "冉熠", "沁瑛", "娇畅", "凤敏", "楚任", "绮舒", "莺俭", "葵歌", "纪诺", "寒北", "赫敬", "芯汝", "顺赫", "莹淑", "丽沙", "曼洁", "励杉", "垚廷", "柳书", "芸雯", "瑗思", "韶芊", "孝卓", "爽治", "斐达", "军禄", "生麟", "俪允", "驰栩", "全朝", "颢炼", "李兰", "涓澄", "同珺", "晔楚", "俐李", "泽臣", "陵椒", "肠准", "城歌", "辛钧", "朦冶", "珑泉", "希淑", "仪玮", "音晴", "歌丹", "幸莎", "允霏", "妍丹", "日菡", "淼有", "珂信", "权章", "卿莺", "泉冉", "蕊莎", "若娴", "能朝", "可芹", "绚琦", "轶或", "桥湛", "林迁", "腾育", "娣伶", "艾心", "剑凌", "尧骥", "宏领", "甜毓", "铖淼", "书梦", "鹭宁", "想眉", "珊蓉", "纳珊", "洋泓", "曦苏", "蔚幸", "乾杉", "诗子", "靖裙", "中辛", "能坤", "千盟", "成桥", "祺嫚", "娅铃", "列坤", "柯腾", "弋雷", "斌锁", "妤荔", "艺园", "昊宪", "实壮", "仲田", "妍颜", "菁萱", "菡萱", "同宁", "拓琨", "玫恬", "韵珠", "隽焘", "育新", "旖椒", "亚翌", "逸兴", "容蓉", "洲品", "泉好", "玥忆", "乾成", "炯桃", "昭举", "恬菡", "瑛颜", "胤湛", "运易", "河佳", "默日", "沙湘", "昌壮", "旋宾", "珑玺", "暖文", "霏卉", "添高", "梅静", "晟达", "琳红", "栋蔚", "令登", "珉麟", "琚蕴", "昀盼", "澎盟", "溪纲", "菁娣", "泽歌", "振义", "沫雯", "彦一", "牧宸", "瀚泳", "刚章", "咪莎", "韵涓", "化宏", "琰烨", "冉舒", "峻锋", "研娓", "兵京", "强舟", "良丽", "季金", "群萍", "焕嘉", "冉星"};

    static final String[] COMMON_SINGLE_NAME = {"一", "万", "三", "东", "丹", "丽", "之", "九", "五", "人", "仗", "仙", "令",
        "伯", "信", "倍", "倒", "假", "兜", "八", "公", "六", "兰", "冬", "凌", "凤", "利", "勋", "单", "南", "卡", "叶", "合",
        "吊", "四", "地", "堇", "墨", "士", "夏", "夜", "大", "天", "夹", "女", "姜", "娘", "婆", "子", "孔", "季", "寒", "寿",
        "小", "岩", "巢", "年", "广", "建", "彩", "心", "扇", "打", "拉", "挂", "指", "掌", "搓", "文", "斛", "日", "旱", "昙",
        "春", "晶", "月", "木", "杉", "杨", "松", "枫", "枸", "柴", "栀", "根", "格", "桃", "桐", "桢", "梅", "棠", "槐", "槿",
        "樱", "殊", "毛", "汉", "泡", "洋", "洲", "活", "浆", "海", "炮", "烟", "燕", "爪", "牙", "牛", "牡", "牵", "特", "狗",
        "玉", "玫", "玲", "玻", "球", "琼", "瑰", "璃", "瓜", "瓣", "生", "白", "百", "盘", "看", "睡", "矮", "石", "碗", "禄",
        "福", "积", "稻", "章", "竹", "竺", "筒", "箭", "紫", "红", "纳", "络", "绿", "罗", "美", "羽", "翘", "翠", "考", "耳",
        "背", "胭", "脂", "舞", "色", "芋", "芍", "芙", "芫", "花", "苍", "苞", "苣", "英", "茉", "茑", "茶", "草", "荚", "荬",
        "药", "荷", "荽", "莉", "莓", "莲", "菊", "菜", "萄", "萝", "落", "葡", "葱", "葵", "蒲", "蒾", "蓉", "蓟", "蓬", "蓼"
        , "蔓", "蔷", "蕙", "蕨", "薇", "薸", "藿", "蘽", "蛇", "蛋", "蜀", "蜞", "蝉", "蟛", "蟹", "血", "角", "豆", "蹄", "车",
        "轴", "迎", "连", "酢", "里", "重", "野", "金", "钟", "锦", "长", "阿", "雀", "雪", "雷", "霄", "青", "非", "革", "韩",
        "韭", "风", "饭", "香", "马", "骨", "鱼", "鸟", "鸡", "鹅", "麦", "黄", "黑", "龙", "龟", "一", "业", "丛", "东", "严",
        "中", "丹", "为", "丽", "义", "习", "书",
        "予", "云", "亚", "京", "亭", "亮", "仁", "令", "仪", "仲", "任", "伊", "众", "优", "会", "伦", "伶", "佳", "侃", "依",
        "俏", "信", "俪", "俭", "倩", "健", "傲", "允", "元", "克", "兰", "兴", "典", "冉", "军", "冰", "冲", "冶", "凌", "凝",
        "凤", "凯", "列", "刚", "利", "前", "剑", "功", "劫", "励", "勃", "勋", "化", "北", "千", "卉", "华", "卓", "南", "博",
        "印", "友", "可", "吉", "君", "含", "启", "咏", "咪", "响", "哲", "唯", "啸", "喆", "喜", "喻", "嘉", "园", "国", "均",
        "垒", "垚", "垣", "城", "培", "墨", "墩", "声", "多", "天", "奇", "奔", "奕", "好", "如", "妍", "妮", "妲", "姗", "姣",
        "姬", "姿", "娅", "娆", "娉", "娓", "娜", "娣", "娥", "娴", "婉", "婕", "婧", "婵", "婷", "媚", "媛", "嫣", "子", "学",
        "宇", "宜", "宝", "实", "宪", "宸", "容", "宽", "宾", "寒", "将", "尉", "尚", "尧", "展", "山", "屹", "岗", "岚", "岩",
        "岭", "岱", "岳", "峥", "峰", "崧", "嵘", "嵩", "川", "巧", "帆", "希", "年", "庄", "庆", "庚", "庭", "延", "廷", "建",
        "开", "弋", "弘", "弛", "强", "彤", "彦", "彩", "彬", "彭", "影", "征", "微", "德", "心", "忆", "忠", "忱", "念", "忻",
        "怀", "思", "怡", "恋", "恬", "恺", "惟", "惠", "想", "意", "慈", "慧", "懿", "戈", "或", "战", "承", "拓", "振", "攀",
        "敏", "文", "斐", "斯", "旋", "旎", "旖", "旗", "日", "旭", "旺", "旻", "昀", "昆", "明", "昕", "星", "春", "昭", "晋",
        "晏", "晓", "晔", "晗", "晨", "晶", "暖", "曙", "曦", "月", "朔", "朗", "望", "朝", "朦", "朵", "杏", "松", "林", "果",
        "枫", "柏", "柯", "柱", "柳", "栋", "根", "格", "桃", "桐", "桑", "桢", "梅", "梦", "椒", "楚", "楠", "榕", "樱", "欢",
        "欣", "歆", "歌", "武", "殉", "毅", "毓", "汝", "江", "沁", "沙", "沛", "沫", "河", "治", "泉", "泓", "波", "泳", "泽",
        "洁", "洋", "津", "洲", "洵", "浩", "浪", "海", "涌", "涓", "润", "淞", "淮", "淳", "添", "渝", "渤", "游", "湛", "溢",
        "溪", "滢", "漪", "漫", "潜", "潮", "澄", "澎", "澜", "灵", "灿", "炯", "炳", "烁", "烨", "烽", "焕", "焘", "焰", "焱",
        "然", "煦", "照", "熙", "熹", "燕", "爱", "爽", "牧", "献", "玫", "玮", "玲", "玺", "珉", "珍", "珏", "珠", "珣", "珺",
        "理", "琚", "琛", "琥", "琦", "琨", "琰", "琳", "琼", "瑛", "瑶", "瑾", "璇", "璋", "璐", "璞", "璟", "甜", "甫", "畅",
        "畏", "皎", "盈", "益", "盛", "真", "睿", "研", "磊", "祺", "禹", "秦", "稳", "章", "童", "竹", "笛", "筝", "筱", "素",
        "红", "纪", "纯", "纳", "绮", "维", "美", "羚", "群", "羿", "翊", "翠", "翰", "翼", "耀", "耘", "耿", "联", "聪", "肖",
        "育", "胡", "胤", "能", "腾", "臣", "臻", "舒", "舜", "航", "舰", "艳", "艺", "芊", "芝", "芬", "芮", "花", "芳", "芹",
        "苏", "苓", "英", "茂", "茉", "茜", "茵", "茹", "荔", "莉", "莎", "莲", "莹", "菊", "菱", "菲", "萌", "萍", "萱", "葵",
        "蓉", "蓓", "蔓", "蔚", "蔷", "蕊", "蕙", "蕴", "薇", "虎", "虹", "蜜", "蝶", "融", "行", "衡", "裙", "言", "讳", "诚",
        "谦", "谨", "贝", "贞", "财", "贵", "起", "越", "跃", "路", "轶", "辉", "辛", "辰", "达", "迁", "迅", "运", "远", "连",
        "逊", "逸", "遥", "郁", "里", "重", "野", "钊", "钟", "钧", "钰", "铃", "铎", "铖", "铭", "铮", "锋", "锟", "锦", "锨",
        "锬", "镇", "闯", "闽", "阔", "队", "隆", "雁", "雄", "雅", "雍", "雨", "雪", "雯", "霁", "霆", "霏", "霓", "霖", "霞",
        "露", "靓", "靖", "韦", "韬", "韵", "韶", "顺", "颂", "领", "颖", "颜", "颢", "风", "飙", "飚", "驰", "骁", "骊", "骞",
        "魁", "鲁", "鸿", "鹃", "鹏", "鹤", "鹭", "鹰", "麒", "黎", "煜"};

    private static String[] componentSurnameArray() {
        String[] surnameArray = new String[SURNAME.length + COMPOUND_SURNAME.length];
        int surnameLen = SURNAME.length;
        int surnameIndex = 0;
        int compoundSurnameLen = COMPOUND_SURNAME.length;
        int compoundSurnameIndex = 0;
        int arrayLen = 0;
        while ((surnameIndex + compoundSurnameIndex) < surnameArray.length) {
            Random random = new Random(System.nanoTime());
            int slen = random.nextInt(surnameLen) % 4;
            int clen = random.nextInt(compoundSurnameLen) % 4;
            int index = 0;
            for (; index < slen && (surnameIndex + index) < surnameLen; index++) {
                surnameArray[arrayLen + index] = SURNAME[surnameIndex + index];
            }
            arrayLen = arrayLen + index;
            surnameIndex = surnameIndex + index;
            index = 0;
            for (; index < clen && (compoundSurnameIndex + index) < compoundSurnameLen; index++) {
                surnameArray[arrayLen + index] = COMPOUND_SURNAME[compoundSurnameIndex + index];
            }
            arrayLen = arrayLen + index;
            compoundSurnameIndex = compoundSurnameIndex + index;
        }
        return surnameArray;
    }

    public static List<String> generateName(int count) {
        Set<String> nameSet = new HashSet<>(count);
        String[] surnameArray = componentSurnameArray();
        int surnameLen = SURNAME.length;
        int singleNameLen = COMMON_SINGLE_NAME.length;
        while (nameSet.size() < count) {
            Random random = new Random(System.nanoTime());
            String name;
            if (0 == (random.nextInt(singleNameLen) % 2)) {
                name = surnameArray[random.nextInt(surnameLen)] +
                    COMMON_SINGLE_NAME[random.nextInt(singleNameLen)];
            } else {
                name = surnameArray[random.nextInt(surnameLen)] +
                    COMMON_SINGLE_NAME[random.nextInt(singleNameLen)] +
                    COMMON_SINGLE_NAME[random.nextInt(singleNameLen)];
            }
//            System.out.println(name);
            nameSet.add(name);
        }
        return new ArrayList<>(nameSet);
    }

    public static void main2(String[] args) {
        long start = System.nanoTime();
        int count = 10;
        List<String> idCardList = generateIdCard(count);
        List<String> phoneList = generatePhone(count);
        List<String> nameList = generateName(count);
        for (int i = 0; i < count; i++) {
            String line = String.format("%11s %18s %s", phoneList.get(i), idCardList.get(i), nameList.get(i));
            System.out.println(line);
        }
        System.out.println("generate duration [" + ((System.nanoTime() - start) / 1000 / 1000) + "]");
    }

    public static void main(String[] args) throws IOException {
        //generateName(100);
        long start = System.nanoTime();
        boolean useRegion = false;
        boolean console = true;
        int count = 20;
        List<String> idCardList = generateIdCard(count);
        List<String> phoneList = generatePhone(count);
        List<String> nameList = generateName(count);
        List<String> lines = new ArrayList<>(count);
        if (useRegion) {
            Map<String, String> regionData = generateRegionDataFormat();
            for (int i = 0; i < count; i++) {
                String idCard = idCardList.get(i);
                String region = Objects.toString(regionData.get(idCard.substring(0, 6)), "");
                String line = String.format("%11s %18s %s %s", phoneList.get(i), idCard, nameList.get(i), region);
                if (console) {
                    System.out.println(line);
                }
                lines.add(line);
            }
            //FileUtils.writeLines(new File("C:\\Users\\Administrator\\Desktop\\temporary-names-region.txt"), "UTF-8", lines, false);
        } else {
            for (int i = 0; i < count; i++) {
                String line = String.format("%11s %18s %s", phoneList.get(i), idCardList.get(i), nameList.get(i));
                if (console) {
                    System.out.println(line);
                }
                lines.add(line);
            }
            // FileUtils.writeLines(new File("C:\\Users\\Administrator\\Desktop\\temporary-names.txt"), "UTF-8", lines, false);
        }
        System.out.println("generate duration [" + ((System.nanoTime() - start) / 1000 / 1000) + "]");
    }

}
