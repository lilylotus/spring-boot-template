package org.example.simple.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link IdCardNoValidatorUtils} 的结构格式、出生日期真实性、校验码校验测试。 */
class IdCardNoValidatorUtilsTest {

    @Test
    void isValid_digitCheckCode_returnsTrue() {
        assertTrue(IdCardNoValidatorUtils.isValid("110101199001010015"));
    }

    @Test
    void isValid_upperCaseXCheckCode_returnsTrue() {
        assertTrue(IdCardNoValidatorUtils.isValid("11010119900101004X"));
    }

    @Test
    void isValid_digitCheckCode2() {
        assertTrue(IdCardNoValidatorUtils.isValid("110107199209181794"));
    }

    @Test
    void isValid_lowerCaseXCheckCode_returnsTrue() {
        assertTrue(IdCardNoValidatorUtils.isValid("11010119900101004x"));
    }

    @Test
    void isValid_realLeapDay_returnsTrue() {
        assertTrue(IdCardNoValidatorUtils.isValid("110101202402290016"));
    }

    @Test
    void isValid_lengthNot18_returnsFalse() {
        assertFalse(IdCardNoValidatorUtils.isValid("1101011990010100"));
        assertFalse(IdCardNoValidatorUtils.isValid("110101199001010015X"));
    }

    @Test
    void isValid_containsNonDigitCharacterOtherThanLastPosition_returnsFalse() {
        assertFalse(IdCardNoValidatorUtils.isValid("11010A199001010015"));
        assertFalse(IdCardNoValidatorUtils.isValid("11010119900101001#"));
    }

    @Test
    void isValid_areaCodeLeadingZero_returnsFalse() {
        assertFalse(IdCardNoValidatorUtils.isValid("010101199001010010"));
    }

    @Test
    void isValid_invalidMonth_returnsFalse() {
        assertFalse(IdCardNoValidatorUtils.isValid("110101199013010010"));
    }

    @Test
    void isValid_invalidDay_returnsFalse() {
        assertFalse(IdCardNoValidatorUtils.isValid("110101199001320010"));
    }

    @Test
    void isValid_nonExistentFebruary29_returnsFalse() {
        assertFalse(IdCardNoValidatorUtils.isValid("110101202302290019"));
    }

    @Test
    void isValid_nonExistentApril31_returnsFalse() {
        assertFalse(IdCardNoValidatorUtils.isValid("11010120230431001X"));
    }

    @Test
    void isValid_birthYearAfterCurrentYear_returnsFalse() {
        assertFalse(IdCardNoValidatorUtils.isValid("110101209901010017"));
    }

    @Test
    void isValid_checkCodeMismatch_returnsFalse() {
        assertFalse(IdCardNoValidatorUtils.isValid("110101199001010016"));
    }

    @Test
    void isValid_nullOrBlank_returnsFalse() {
        assertFalse(IdCardNoValidatorUtils.isValid(null));
        assertFalse(IdCardNoValidatorUtils.isValid(""));
        assertFalse(IdCardNoValidatorUtils.isValid("   "));
    }

    @Test
    void isValid_fifteenDigitLegacyFormat_returnsFalse() {
        assertFalse(IdCardNoValidatorUtils.isValid("110101900101001"));
    }
}
