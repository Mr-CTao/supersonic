package com.tencent.supersonic.headless.server.semantic.modeling;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * AI 语义建模场景的共享敏感信息分类器。
 *
 * <p>
 * 职责说明：统一识别敏感物理字段、字段注释和常见敏感文本，供草稿上下文脱敏与阶段 4 验证门禁复用，避免两套规则随版本演进发生漂移。分类器只持有编译后的只读正则表达式，所有判断均使用方法局部变量，
 * 因此可以作为 Spring 单例在并发请求间安全复用，无需额外加锁。
 * </p>
 */
@Component
public class SemanticModelingSensitivityClassifier {

    /** 对外统一使用的不可逆掩码，避免敏感内容进入 Prompt、日志或验证报告。 */
    public static final String MASKED_TEXT = "[MASKED]";

    private static final Pattern SENSITIVE_COLUMN = Pattern
            .compile("(?i).*(password|passwd|pwd|secret|token|credential|api[_-]?key|authorization|"
                    + "cookie|session|phone|mobile|email|contact|"
                    + "id[_-]?card|ssn|address|customer[_-]?name|user[_-]?name|real[_-]?name|"
                    + "bank[_-]?account|account[_-]?no|card[_-]?no|credit[_-]?card|salary|"
                    + "payroll|date[_-]?of[_-]?birth|passport|private[_-]?key|"
                    + "full[_-]?name|first[_-]?name|last[_-]?name|姓名|客户姓名|用户名|手机号|"
                    + "电话|联系方式|邮箱|身份证|地址|银行卡|银行账号|银行账户|结算账号|" + "工资|薪资|护照|出生日期|私钥|密码|口令|令牌|密钥).*");

    private static final Pattern SENSITIVE_VALUE =
            Pattern.compile("(?i)(?:1[3-9]\\d{9}|(?<!\\d)\\d{16,19}(?!\\d)|"
                    + "(?<![A-Za-z0-9])[EGDSPHM]\\d{8}(?![A-Za-z0-9])|"
                    + "-----BEGIN(?: [A-Z]+)? PRIVATE KEY-----|"
                    + "[1-9]\\d{5}(?:18|19|20)\\d{2}(?:0[1-9]|1[0-2])"
                    + "(?:0[1-9]|[12]\\d|3[01])\\d{3}[0-9X]|[^@\\s]+@[^@\\s]+\\.[^@\\s]+|"
                    + "bearer\\s+[A-Za-z0-9._~+/=-]+|(?:sk|rk)-[A-Za-z0-9_-]{8,}|"
                    + "(?:eyJ[A-Za-z0-9_-]+\\.){2}[A-Za-z0-9_-]+|"
                    + "(?:api[_-]?key|access[_-]?token|refresh[_-]?token|client[_-]?secret|token|"
                    + "secret|password|passwd|pwd|bank[_-]?account|account[_-]?no|card[_-]?no|"
                    + "credit[_-]?card|salary|payroll|date[_-]?of[_-]?birth|passport|"
                    + "private[_-]?key)\\s*[:=]\\s*[\"']?[^,\\s\"']{4,}|"
                    + "(?:银行卡|银行账号|银行账户|结算账号|工资|薪资|护照|出生日期|私钥)" + "\\s*[:：=]\\s*[^,\\s]{4,})");

    /**
     * 判断字段名或字段注释是否具有敏感信息特征。
     *
     * <p>
     * 调用示例：{@code classifier.isSensitiveColumn("customer_phone", "客户手机号")}。
     * </p>
     *
     * @param columnName 物理字段名，可以为空。
     * @param comment 数据库字段注释，可以为空。
     * @return 任一输入命中敏感规则时返回 {@code true}，否则返回 {@code false}。
     */
    public boolean isSensitiveColumn(String columnName, String comment) {
        return matchesSensitiveDescriptor(columnName) || matchesSensitiveDescriptor(comment);
    }

    /**
     * 对可能包含敏感值的文本执行安全脱敏。
     *
     * <p>
     * 调用示例：{@code classifier.sanitizeText("联系人 owner@example.com")} 将返回固定掩码。
     * 为避免混合文本保留敏感片段，只要任意子串命中规则就遮盖整段文本；未命中时保持原值，空值保持为空。
     * </p>
     *
     * @param value 待检查文本，可以为空。
     * @return 已脱敏文本；命中敏感规则时返回 {@link #MASKED_TEXT}。
     */
    public String sanitizeText(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return SENSITIVE_VALUE.matcher(value).find() ? MASKED_TEXT : value;
    }

    /**
     * 判断文本是否包含敏感值而不返回或记录匹配内容。
     *
     * @param value 待检查文本，可以为空。
     * @return 命中邮箱、手机号、令牌等敏感值规则时返回 {@code true}。
     */
    public boolean containsSensitiveValue(String value) {
        return StringUtils.isNotEmpty(value) && SENSITIVE_VALUE.matcher(value).find();
    }

    /** 判断单个字段描述是否命中敏感规则。 */
    private boolean matchesSensitiveDescriptor(String value) {
        return StringUtils.isNotBlank(value) && SENSITIVE_COLUMN.matcher(value).matches();
    }
}
