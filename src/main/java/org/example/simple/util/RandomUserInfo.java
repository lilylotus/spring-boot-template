package org.example.simple.util;

/**
 * {@link RandomUserInfoUtils#generateUsers(int)} 生成的一条组合用户数据。
 * <p>
 * {@code region} 是 {@code idCardNo} 的地区码在内置地区码表中对应的地区名称，二者保证一致；
 * {@code name}、{@code phoneNumber}、{@code idCardNo} 在同一次 {@code generateUsers} 调用返回的结果中各自唯一，
 * 但 {@code region} 不保证唯一。
 *
 * @param name 随机姓名
 * @param phoneNumber 随机手机号
 * @param idCardNo 随机且可通过 {@link IdCardNoValidatorUtils#isValid(String)} 校验的身份证号
 * @param region idCardNo 地区码对应的地区名称
 */
public record RandomUserInfo(String name, String phoneNumber, String idCardNo, String region) {
}
