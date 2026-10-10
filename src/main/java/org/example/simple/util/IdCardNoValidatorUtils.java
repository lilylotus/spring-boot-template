package org.example.simple.util;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.regex.Pattern;

/**
 * 身份证号校验工具类：校验中国大陆 18 位居民身份证号（GB 11643-1999 现行标准）的格式与准确性。
 * <p>
 * 校验分三个层面：
 * <ul>
 *   <li>结构格式：用正则表达式校验 6 位地区码（首位非 0）+ 8 位出生日期（年份以
 *       {@code 18}/{@code 19}/{@code 20} 开头）+ 3 位顺序码 + 1 位校验位（数字或 {@code X}/{@code x}）；</li>
 *   <li>出生日期真实性：正则只能排除格式明显不对的日期，无法识别"2 月 30 日"这类格式正确但实际不存在的日期，
 *       需要再用 {@link LocalDate#of(int, int, int)} 二次校验，并要求出生年份不晚于当前年份；</li>
 *   <li>校验码正确性：按 GB 11643 附录 B（等价于 {@code ISO 7064:1983.MOD 11-2}）算法，用前 17 位加权求和后
 *       与第 18 位比较。</li>
 * </ul>
 * <p>
 * 只支持 18 位现行标准，不支持 1999 年前已废止的 15 位老身份证号格式；也不校验地区码是否为《GB/T 2260》中
 * 真实存在的行政区划代码——该码表随行政区划调整频繁变化，维护码表超出本工具"格式与准确性校验"的职责范围。
 * <p>
 * {@link #isValid(String)} 对任何不合法的输入（包括 {@code null}、空白字符串）统一返回 {@code false}，
 * 不抛出异常：校验用户输入本身格式是否正确正是该方法存在的意义，不合法的输入是预期的业务结果，不是调用错误。
 * <p>
 * 本工具类无状态，所有方法均可被多线程并发调用。
 */
public final class IdCardNoValidatorUtils {

    /**
     * 18 位身份证号结构校验正则：6 位地区码（首位非 0）+ 4 位年份（{@code 18xx}/{@code 19xx}/{@code 20xx}）
     * + 2 位月份（{@code 01-12}）+ 2 位日期（{@code 01-31}）+ 3 位顺序码 + 1 位校验位（数字或 X/x）。
     */
    private static final Pattern STRUCTURE_PATTERN = Pattern.compile(
            "^[1-9]\\d{5}(?:18|19|20)\\d{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12]\\d|3[01])\\d{3}[0-9Xx]$");

    /** 前 17 位各位的加权系数，顺序对应第 1 位到第 17 位，来自 GB 11643 附录 B。 */
    private static final int[] CHECK_CODE_WEIGHTS = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};

    /** 加权求和对 11 取余后的校验码映射表，下标为余数，来自 GB 11643 附录 B。 */
    private static final char[] CHECK_CODES = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};

    private static final int ID_CARD_LENGTH = 18;
    private static final int CHECK_CODE_MODULUS = 11;

    private IdCardNoValidatorUtils() {
    }

    /**
     * 校验 {@code idCardNo} 是否是一个准确的 18 位居民身份证号。
     *
     * @param idCardNo 待校验的身份证号，可以为 {@code null} 或任意字符串
     * @return 结构格式、出生日期真实性、校验码均校验通过时返回 {@code true}；否则返回 {@code false}
     */
    public static boolean isValid(String idCardNo) {
        if (idCardNo == null || idCardNo.isBlank()) {
            return false;
        }
        if (!STRUCTURE_PATTERN.matcher(idCardNo).matches()) {
            return false;
        }
        if (!isBirthDateValid(idCardNo)) {
            return false;
        }
        return isCheckCodeValid(idCardNo);
    }

    /**
     * 校验第 7-14 位出生日期段是否为真实存在的公历日期，且出生年份不晚于当前年份。
     * <p>
     * 调用前已由 {@link #STRUCTURE_PATTERN} 保证年月日各段均在格式允许的数字范围内，这里只需要排除
     * "4 月 31 日""2 月 30 日"这类格式正确但实际不存在的日期。
     */
    private static boolean isBirthDateValid(String idCardNo) {
        int year = Integer.parseInt(idCardNo.substring(6, 10));
        int month = Integer.parseInt(idCardNo.substring(10, 12));
        int day = Integer.parseInt(idCardNo.substring(12, 14));
        if (year > LocalDate.now().getYear()) {
            return false;
        }
        try {
            LocalDate.of(year, month, day);
        } catch (DateTimeException exception) {
            return false;
        }
        return true;
    }

    /**
     * 按 GB 11643 附录 B 算法，用前 17 位数字加权求和后对 11 取余，查表得到期望校验码，
     * 与第 18 位（转大写后）比较。
     */
    private static boolean isCheckCodeValid(String idCardNo) {
        int sum = 0;
        for (int i = 0; i < CHECK_CODE_WEIGHTS.length; i++) {
            sum += (idCardNo.charAt(i) - '0') * CHECK_CODE_WEIGHTS[i];
        }
        char expectedCheckCode = CHECK_CODES[sum % CHECK_CODE_MODULUS];
        char actualCheckCode = Character.toUpperCase(idCardNo.charAt(ID_CARD_LENGTH - 1));
        return expectedCheckCode == actualCheckCode;
    }
}
