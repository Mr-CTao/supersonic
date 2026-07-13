package com.tencent.supersonic.headless.server.semantic.modeling;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SemanticModelingSensitivityClassifier} 的字段识别与文本脱敏单元测试。
 *
 * <p>
 * 每个用例创建独立分类器，测试不共享可变状态，也不依赖 Spring 容器。
 * </p>
 */
class SemanticModelingSensitivityClassifierTest {

    private final SemanticModelingSensitivityClassifier classifier =
            new SemanticModelingSensitivityClassifier();

    /** 英文敏感字段名及数据库注释必须被统一识别。 */
    @Test
    void shouldRecognizeEnglishSensitiveColumnsAndComments() {
        assertTrue(classifier.isSensitiveColumn("customer_phone", null));
        assertTrue(classifier.isSensitiveColumn("mobile", null));
        assertTrue(classifier.isSensitiveColumn("email_address", null));
        assertTrue(classifier.isSensitiveColumn("id_card_no", null));
        assertTrue(classifier.isSensitiveColumn("shipping_address", null));
        assertTrue(classifier.isSensitiveColumn("customer_name", null));
        assertTrue(classifier.isSensitiveColumn("password_hash", null));
        assertTrue(classifier.isSensitiveColumn("access_token", null));
        assertTrue(classifier.isSensitiveColumn("client_secret", null));
        assertTrue(classifier.isSensitiveColumn("bank_account", null));
        assertTrue(classifier.isSensitiveColumn("account_no", null));
        assertTrue(classifier.isSensitiveColumn("card_no", null));
        assertTrue(classifier.isSensitiveColumn("credit_card", null));
        assertTrue(classifier.isSensitiveColumn("salary", null));
        assertTrue(classifier.isSensitiveColumn("payroll_amount", null));
        assertTrue(classifier.isSensitiveColumn("date_of_birth", null));
        assertTrue(classifier.isSensitiveColumn("passport_no", null));
        assertTrue(classifier.isSensitiveColumn("private_key", null));
        assertTrue(classifier.isSensitiveColumn("external_code", "Customer phone number"));
        assertFalse(classifier.isSensitiveColumn("warehouse_code", "仓库编码"));
    }

    /** 中文字段名与注释中的姓名、联系方式、证件和凭据描述必须被识别。 */
    @Test
    void shouldRecognizeChineseSensitiveColumnsAndComments() {
        assertTrue(classifier.isSensitiveColumn("客户姓名", null));
        assertTrue(classifier.isSensitiveColumn("备用手机号", null));
        assertTrue(classifier.isSensitiveColumn("contact_code", "联系人邮箱"));
        assertTrue(classifier.isSensitiveColumn("credential_value", "登录密码"));
        assertTrue(classifier.isSensitiveColumn("identity_no", "身份证号码"));
        assertTrue(classifier.isSensitiveColumn("home_location", "家庭地址"));
        assertTrue(classifier.isSensitiveColumn("settlement_code", "结算账号"));
        assertTrue(classifier.isSensitiveColumn("monthly_income", "工资"));
        assertTrue(classifier.isSensitiveColumn("travel_document", "护照号码"));
    }

    /** 混合文本中的邮箱、手机号和常见凭据必须整段遮盖，普通业务文本保持不变。 */
    @Test
    void shouldSanitizeSensitiveTextWithoutChangingSafeValues() {
        assertEquals(SemanticModelingSensitivityClassifier.MASKED_TEXT,
                classifier.sanitizeText("请联系 owner@example.com 确认库存"));
        assertEquals(SemanticModelingSensitivityClassifier.MASKED_TEXT,
                classifier.sanitizeText("备用电话 13800138000"));
        assertEquals(SemanticModelingSensitivityClassifier.MASKED_TEXT,
                classifier.sanitizeText("Authorization=Bearer nested-token-123"));
        assertEquals(SemanticModelingSensitivityClassifier.MASKED_TEXT,
                classifier.sanitizeText("client_secret=super-secret-123"));
        assertEquals(SemanticModelingSensitivityClassifier.MASKED_TEXT,
                classifier.sanitizeText("bank_account=6222020202020202020"));
        assertEquals(SemanticModelingSensitivityClassifier.MASKED_TEXT,
                classifier.sanitizeText("passport=E12345678"));
        assertEquals(SemanticModelingSensitivityClassifier.MASKED_TEXT,
                classifier.sanitizeText("salary=25000"));
        assertEquals(SemanticModelingSensitivityClassifier.MASKED_TEXT,
                classifier.sanitizeText("-----BEGIN PRIVATE KEY-----"));
        assertEquals("华北区域", classifier.sanitizeText("华北区域"));
        assertEquals("", classifier.sanitizeText(""));
        assertNull(classifier.sanitizeText(null));
    }
}
