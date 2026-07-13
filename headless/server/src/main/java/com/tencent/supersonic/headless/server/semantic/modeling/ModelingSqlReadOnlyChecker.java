package com.tencent.supersonic.headless.server.semantic.modeling;

import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.NextValExpression;
import net.sf.jsqlparser.expression.VariableAssignment;
import net.sf.jsqlparser.expression.operators.relational.ExistsExpression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.ParenthesedFromItem;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.TableFunction;
import net.sf.jsqlparser.statement.select.WithItem;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * AI 语义建模验证阶段的 SQL 只读语法门禁。
 *
 * <p>
 * 职责说明：使用 JSqlParser 递归检查完整查询 AST，仅接受一条能够由当前数据库方言的显式纯函数策略证明无副作用的 SELECT。组件不会连接数据源、执行 SQL 或记录 SQL
 * 原文；所有分析状态均为方法局部变量，Spring 单例并发调用无需共享锁。
 * </p>
 */
@Component
public class ModelingSqlReadOnlyChecker {

    public static final String CODE_READ_ONLY = "READ_ONLY";
    public static final String CODE_EMPTY_SQL = "EMPTY_SQL";
    public static final String CODE_PARSE_ERROR = "SQL_PARSE_ERROR";
    public static final String CODE_MULTIPLE_STATEMENTS = "MULTIPLE_STATEMENTS";
    public static final String CODE_NON_SELECT = "NON_SELECT_STATEMENT";
    public static final String CODE_SIDE_EFFECT = "SELECT_SIDE_EFFECT";
    public static final String CODE_FUNCTION_NOT_PROVEN = "FUNCTION_PURITY_NOT_PROVEN";

    private static final Pattern SIDE_EFFECT_TOKENS =
            Pattern.compile("(?is)(?:\\bINTO\\s+(?:OUTFILE|DUMPFILE)\\b|\\bINTO\\b|"
                    + "\\bFOR\\s+(?:UPDATE|SHARE|NO\\s+KEY\\s+UPDATE|KEY\\s+SHARE)\\b|"
                    + "\\bLOCK\\s+IN\\s+SHARE\\s+MODE\\b)");

    /**
     * 与数据库无关且项目翻译链路允许使用的纯查询函数。集合不可变，新增函数必须先证明无会话、锁或持久化副作用。
     */
    private static final Set<String> COMMON_SAFE_FUNCTIONS =
            Set.of("abs", "avg", "cast", "ceil", "ceiling", "coalesce", "concat", "count",
                    "current_date", "current_time", "current_timestamp", "date", "extract", "floor",
                    "greatest", "least", "length", "lower", "ltrim", "max", "min", "mod", "nullif",
                    "replace", "round", "rtrim", "substring", "sum", "trim", "upper");

    /** PostgreSQL 明确无副作用且阶段 4 翻译/预览常用的函数。 */
    private static final Set<String> POSTGRES_SAFE_FUNCTIONS =
            Set.of("age", "btrim", "date_part", "date_trunc", "initcap", "now", "position",
                    "split_part", "strpos", "to_char", "to_date", "to_number", "to_timestamp");

    /** MySQL 明确无副作用且阶段 4 翻译/预览常用的函数。 */
    private static final Set<String> MYSQL_SAFE_FUNCTIONS =
            Set.of("char_length", "convert", "curdate", "date_add", "date_format", "date_sub",
                    "datediff", "day", "if", "ifnull", "instr", "locate", "month", "now",
                    "str_to_date", "timestampdiff", "unix_timestamp", "year");

    /** 已知会推进序列、持有锁或修改会话状态的函数；即使方言元数据错误也应保守拒绝。 */
    private static final Set<String> SIDE_EFFECT_FUNCTIONS =
            Set.of("get_lock", "pg_advisory_lock", "pg_advisory_lock_shared", "pg_advisory_unlock",
                    "pg_advisory_unlock_all", "pg_advisory_unlock_shared", "release_all_locks",
                    "release_lock", "set_config", "setval", "nextval");

    /**
     * 使用保守通用方言校验 SQL。
     *
     * <p>
     * 调用示例：{@code checker.validate("SELECT COUNT(*) FROM orders")}。
     * </p>
     *
     * @param sql 待校验 SQL；不会被执行或记录日志。
     * @return 包含只读结论、稳定代码和脱敏说明的校验结果。
     */
    public CheckResult validate(String sql) {
        return validate(sql, null);
    }

    /**
     * 按数据源元数据声明的数据库方言校验单条无副作用查询。
     *
     * <p>
     * 调用示例：{@code checker.validate("SELECT DATE_TRUNC('day', created_at) FROM orders", "POSTGRESQL")}。
     * 未知函数不会按“非 denylist 即安全”处理，而是返回不可证明代码并阻断验证。
     * </p>
     *
     * @param sql 待校验 SQL；不会被执行或记录日志。
     * @param databaseType 来自数据源元数据的数据库类型，不从 SQL 文本猜测。
     * @return 包含只读结论、稳定代码和脱敏说明的校验结果。
     */
    public CheckResult validate(String sql, String databaseType) {
        if (StringUtils.isBlank(sql)) {
            return denied(CODE_EMPTY_SQL, "SQL 不能为空");
        }

        // 方言写出语法有些无法被 JSqlParser 建模，因此仅对去除字面量、标识符和注释后的 token 做前置拦截。
        if (SIDE_EFFECT_TOKENS.matcher(stripQuotedTextAndComments(sql)).find()) {
            return denied(CODE_SIDE_EFFECT, "查询包含锁定或写出副作用语法");
        }

        try {
            Statements statements = CCJSqlParserUtil.parseStatements(sql);
            List<Statement> parsed = statements;
            if (parsed.size() != 1) {
                return denied(CODE_MULTIPLE_STATEMENTS, "只允许提交一条查询语句");
            }
            Statement statement = parsed.get(0);
            if (!(statement instanceof Select select) || !isSupportedSelect(select)) {
                return denied(CODE_NON_SELECT, "只允许 SELECT 查询语句");
            }
            AstInspection inspection = new AstInspection(databaseType);
            inspection.inspectSelect(select);
            if (inspection.failureCode != null) {
                return denied(inspection.failureCode, inspection.failureMessage);
            }
            return new CheckResult(true, CODE_READ_ONLY, "SQL 为单条只读查询");
        } catch (JSQLParserException | RuntimeException exception) {
            // 解析异常经常携带 SQL 片段，固定文案可防止条件值或内部语法细节进入 API 响应。
            return denied(CODE_PARSE_ERROR, "SQL 无法安全解析");
        }
    }

    /** 拒绝 VALUES/TABLE 等虽属于 Select 层次但不符合阶段 4 查询契约的根节点。 */
    private boolean isSupportedSelect(Select select) {
        if (select instanceof PlainSelect) {
            return true;
        }
        if (select instanceof ParenthesedSelect parenthesedSelect) {
            return parenthesedSelect.getSelect() != null
                    && isSupportedSelect(parenthesedSelect.getSelect());
        }
        if (select instanceof SetOperationList setOperationList) {
            return setOperationList.getSelects() != null && !setOperationList.getSelects().isEmpty()
                    && setOperationList.getSelects().stream().allMatch(this::isSupportedSelect);
        }
        return false;
    }

    /** 创建稳定且不携带 SQL 原文的拒绝结果。 */
    private CheckResult denied(String code, String message) {
        return new CheckResult(false, code, message);
    }

    /** 去除字面量、引用标识符和注释，仅保留可执行关键字用于不能被 AST 建模的危险词法预检。 */
    private String stripQuotedTextAndComments(String sql) {
        StringBuilder result = new StringBuilder(sql.length());
        int index = 0;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (current == '-' && index + 1 < sql.length() && sql.charAt(index + 1) == '-') {
                index = skipLineComment(sql, index + 2, result);
            } else if (current == '#') {
                index = skipLineComment(sql, index + 1, result);
            } else if (current == '/' && index + 1 < sql.length() && sql.charAt(index + 1) == '*') {
                index = skipBlockComment(sql, index + 2, result);
            } else if (current == '\'' || current == '"' || current == '`') {
                index = skipQuoted(sql, index + 1, current, result);
            } else if (current == '[') {
                index = skipQuoted(sql, index + 1, ']', result);
            } else {
                result.append(Character.toUpperCase(current));
                index++;
            }
        }
        return result.toString().toUpperCase(Locale.ROOT);
    }

    /** 跳过单行注释并保留 token 分隔符。 */
    private int skipLineComment(String sql, int index, StringBuilder result) {
        while (index < sql.length() && sql.charAt(index) != '\n' && sql.charAt(index) != '\r') {
            index++;
        }
        result.append(' ');
        return index;
    }

    /** 跳过块注释；未闭合注释交由 JSqlParser 拒绝。 */
    private int skipBlockComment(String sql, int index, StringBuilder result) {
        while (index + 1 < sql.length()
                && !(sql.charAt(index) == '*' && sql.charAt(index + 1) == '/')) {
            index++;
        }
        result.append(' ');
        return Math.min(sql.length(), index + 2);
    }

    /** 跳过字符串或引用标识符，兼容反斜杠和 SQL 双引号转义。 */
    private int skipQuoted(String sql, int index, char quote, StringBuilder result) {
        while (index < sql.length()) {
            char current = sql.charAt(index);
            if (current == '\\' && index + 1 < sql.length()) {
                index += 2;
                continue;
            }
            if (current == quote) {
                if (index + 1 < sql.length() && sql.charAt(index + 1) == quote) {
                    index += 2;
                    continue;
                }
                result.append(' ');
                return index + 1;
            }
            index++;
        }
        result.append(' ');
        return index;
    }

    /**
     * 单次请求的 AST 检查器。失败状态不跨请求共享，避免并发验证互相污染。
     */
    private static final class AstInspection extends ExpressionVisitorAdapter {

        private final String dialect;
        private String failureCode;
        private String failureMessage;

        private AstInspection(String databaseType) {
            this.dialect = StringUtils.defaultString(databaseType).trim().toUpperCase(Locale.ROOT);
        }

        /** 递归检查主查询、CTE、集合分支和所有表达式入口。 */
        private void inspectSelect(Select select) {
            if (failed()) {
                return;
            }
            if (select.getForClause() != null) {
                rejectSideEffect();
                return;
            }
            // LIMIT/OFFSET/TOP/FETCH 也能承载表达式，不能把它们排除在函数副作用遍历之外。
            if (select.getLimit() != null) {
                inspectExpression(select.getLimit().getRowCount());
                inspectExpression(select.getLimit().getOffset());
                if (select.getLimit().getByExpressions() != null) {
                    select.getLimit().getByExpressions().forEach(this::inspectExpression);
                }
            }
            if (select.getOffset() != null) {
                inspectExpression(select.getOffset().getOffset());
            }
            if (select.getFetch() != null) {
                inspectExpression(select.getFetch().getExpression());
            }
            inspectWithItems(select.getWithItemsList());
            if (select instanceof PlainSelect plainSelect) {
                inspectPlainSelect(plainSelect);
            } else if (select instanceof ParenthesedSelect parenthesedSelect) {
                inspectSelect(parenthesedSelect.getSelect());
            } else if (select instanceof SetOperationList setOperationList) {
                for (Select branch : setOperationList.getSelects()) {
                    inspectSelect(branch);
                }
                inspectOrderBy(setOperationList.getOrderByElements());
            } else {
                rejectSideEffect();
            }
        }

        /** CTE 必须和主查询接受同一副作用与函数纯度策略。 */
        private void inspectWithItems(List<WithItem> withItems) {
            if (withItems == null) {
                return;
            }
            for (WithItem withItem : withItems) {
                if (withItem.getSelect() == null) {
                    rejectSideEffect();
                    return;
                }
                inspectSelect(withItem.getSelect());
            }
        }

        /** 覆盖 PlainSelect 中所有能够承载函数、赋值或子查询的表达式位置。 */
        private void inspectPlainSelect(PlainSelect select) {
            if (select.getIntoTables() != null && !select.getIntoTables().isEmpty()
                    || select.getIntoTempTable() != null || select.getForMode() != null
                    || select.getForUpdateTable() != null || select.getWait() != null
                    || select.isNoWait() || select.isSkipLocked()) {
                rejectSideEffect();
                return;
            }
            if (select.getSelectItems() != null) {
                for (SelectItem<?> item : select.getSelectItems()) {
                    inspectExpression(item.getExpression());
                }
            }
            if (select.getTop() != null) {
                inspectExpression(select.getTop().getExpression());
            }
            if (select.getDistinct() != null && select.getDistinct().getOnSelectItems() != null) {
                select.getDistinct().getOnSelectItems()
                        .forEach(item -> inspectExpression(item.getExpression()));
            }
            if (select.getGroupBy() != null) {
                if (select.getGroupBy().getGroupByExpressions() != null) {
                    inspectExpressionItems(select.getGroupBy().getGroupByExpressions());
                }
                if (select.getGroupBy().getGroupingSets() != null) {
                    select.getGroupBy().getGroupingSets().forEach(this::inspectExpressionItems);
                }
            }
            inspectExpression(select.getOracleHierarchical());
            if (select.getWindowDefinitions() != null) {
                select.getWindowDefinitions().forEach(window -> {
                    inspectOrderBy(window.getOrderByElements());
                    if (window.getPartitionExpressionList() != null) {
                        inspectExpressionItems(window.getPartitionExpressionList());
                    }
                });
            }
            inspectFromItem(select.getFromItem());
            inspectExpression(select.getWhere());
            inspectExpression(select.getHaving());
            inspectExpression(select.getQualify());
            inspectOrderBy(select.getOrderByElements());
            if (select.getJoins() != null) {
                for (Join join : select.getJoins()) {
                    inspectFromItem(join.getRightItem());
                    if (join.getOnExpressions() != null) {
                        join.getOnExpressions().forEach(this::inspectExpression);
                    }
                }
            }
        }

        /** FROM/JOIN 中的子查询、括号连接和表函数必须递归检查，普通表不包含可执行表达式。 */
        private void inspectFromItem(FromItem fromItem) {
            if (fromItem instanceof ParenthesedSelect parenthesedSelect) {
                inspectSelect(parenthesedSelect.getSelect());
            } else if (fromItem instanceof ParenthesedFromItem parenthesedFromItem) {
                inspectFromItem(parenthesedFromItem.getFromItem());
                if (parenthesedFromItem.getJoins() != null) {
                    for (Join join : parenthesedFromItem.getJoins()) {
                        inspectFromItem(join.getRightItem());
                        if (join.getOnExpressions() != null) {
                            join.getOnExpressions().forEach(this::inspectExpression);
                        }
                    }
                }
            } else if (fromItem instanceof TableFunction tableFunction) {
                inspectExpression(tableFunction.getFunction());
            } else if (fromItem instanceof Select nestedSelect) {
                inspectSelect(nestedSelect);
            } else if (fromItem != null && !(fromItem instanceof Table)) {
                // 新增或当前未建模的 FROM 节点不能被默认为无副作用。
                failureCode = CODE_FUNCTION_NOT_PROVEN;
                failureMessage = "查询来源的只读性无法确认";
            }
        }

        /** 检查集合尾部或普通查询的 ORDER BY 表达式。 */
        private void inspectOrderBy(List<OrderByElement> orderByElements) {
            if (orderByElements != null) {
                orderByElements.forEach(element -> inspectExpression(element.getExpression()));
            }
        }

        /** 使用 JSqlParser visitor 深度遍历函数参数、CASE、布尔条件和算术表达式。 */
        private void inspectExpression(Expression expression) {
            if (!failed() && expression != null) {
                expression.accept(this);
            }
        }

        /** 兼容 JSqlParser 4.9 的原始 ExpressionList 泛型，同时拒绝静默跳过未知元素。 */
        private void inspectExpressionItems(Iterable<?> expressions) {
            for (Object candidate : expressions) {
                if (candidate instanceof Expression expression) {
                    inspectExpression(expression);
                } else {
                    failureCode = CODE_FUNCTION_NOT_PROVEN;
                    failureMessage = "查询表达式的只读性无法确认";
                    return;
                }
            }
        }

        /** 函数必须是无 schema 限定的显式纯函数，或方言白名单成员。 */
        @Override
        public void visit(Function function) {
            String functionName = normalizeFunctionName(function);
            if (SIDE_EFFECT_FUNCTIONS.contains(functionName)) {
                rejectSideEffect();
                return;
            }
            if (!isSafeFunction(function, functionName)) {
                failureCode = CODE_FUNCTION_NOT_PROVEN;
                failureMessage = "查询函数的只读性无法确认";
                return;
            }
            super.visit(function);
        }

        /** MySQL 用户变量赋值会修改会话状态，必须无条件阻断。 */
        @Override
        public void visit(VariableAssignment assignment) {
            rejectSideEffect();
        }

        /** 序列 NEXT VALUE 节点会推进共享序列，不能因 SELECT 根节点而放行。 */
        @Override
        public void visit(NextValExpression expression) {
            rejectSideEffect();
        }

        /** 表达式中的括号子查询需要切回 Select AST 递归入口。 */
        @Override
        public void visit(ParenthesedSelect select) {
            inspectSelect(select.getSelect());
        }

        /** EXISTS 的右侧查询在 4.9 中不会稳定委派给默认 SelectVisitor，需显式切回查询入口。 */
        @Override
        public void visit(ExistsExpression existsExpression) {
            Expression right = existsExpression.getRightExpression();
            if (right instanceof ParenthesedSelect select) {
                inspectSelect(select.getSelect());
            } else {
                inspectExpression(right);
            }
        }

        /** 只接受无 schema 限定函数，防止用户 schema 用同名函数伪装标准纯函数。 */
        private boolean isSafeFunction(Function function, String functionName) {
            if (StringUtils.isBlank(functionName) || function.getMultipartName() != null
                    && function.getMultipartName().size() > 1) {
                return false;
            }
            if (COMMON_SAFE_FUNCTIONS.contains(functionName)) {
                return true;
            }
            if ("POSTGRESQL".equals(dialect) || "POSTGRES".equals(dialect)) {
                return POSTGRES_SAFE_FUNCTIONS.contains(functionName);
            }
            if ("MYSQL".equals(dialect)) {
                return MYSQL_SAFE_FUNCTIONS.contains(functionName);
            }
            return false;
        }

        /** 函数名来自 AST 元数据，不读取参数或序列化 SQL。 */
        private String normalizeFunctionName(Function function) {
            List<String> multipartName = function.getMultipartName();
            String name = multipartName == null || multipartName.isEmpty() ? function.getName()
                    : multipartName.get(multipartName.size() - 1);
            name = StringUtils.defaultString(name).trim();
            int qualifier = name.lastIndexOf('.');
            return (qualifier >= 0 ? name.substring(qualifier + 1) : name).toLowerCase(Locale.ROOT);
        }

        /** 记录首个稳定失败原因，后续遍历不能用较弱结论覆盖。 */
        private void rejectSideEffect() {
            if (!failed()) {
                failureCode = CODE_SIDE_EFFECT;
                failureMessage = "查询包含会话、锁、序列或写出副作用";
            }
        }

        private boolean failed() {
            return failureCode != null;
        }
    }

    /**
     * SQL 只读门禁结果。
     *
     * @param readOnly 是否为单条可证明无副作用查询。
     * @param code 稳定结果代码，供验证报告和前端映射。
     * @param message 不包含 SQL 原文或函数参数的用户可读说明。
     */
    public record CheckResult(boolean readOnly, String code, String message) {}
}
