package com.tencent.supersonic.headless.server.semantic.modeling;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ModelingSqlReadOnlyChecker} 的 SQL AST 门禁单元测试。
 *
 * <p>
 * Checker 不连接数据库且无共享可变状态，每个测试仅验证确定性的语法分类结果。
 * </p>
 */
class ModelingSqlReadOnlyCheckerTest {

    private final ModelingSqlReadOnlyChecker checker = new ModelingSqlReadOnlyChecker();

    /** 普通 SELECT、安全 CTE、集合查询以及字面量中的敏感关键字必须允许通过。 */
    @Test
    void shouldAllowSingleReadOnlySelects() {
        assertReadOnly("SELECT order_id, quantity FROM orders WHERE quantity > 0");
        assertReadOnly("WITH recent AS (SELECT * FROM orders WHERE created_at >= CURRENT_DATE) "
                + "SELECT * FROM recent");
        assertReadOnly("(SELECT id FROM orders) UNION ALL (SELECT id FROM archived_orders)");
        assertReadOnly("SELECT 'FOR UPDATE' AS note, 'INTO OUTFILE' AS another_note");
        assertReadOnly("SELECT id FROM orders /* FOR UPDATE */ -- INTO OUTFILE\nWHERE id > 0");
    }

    /** DML、DDL 以及 VALUES/TABLE 等非 SELECT 查询根节点必须被拒绝。 */
    @Test
    void shouldRejectNonSelectStatements() {
        assertDenied("UPDATE orders SET status = 'DONE'",
                ModelingSqlReadOnlyChecker.CODE_NON_SELECT);
        assertDenied("DELETE FROM orders", ModelingSqlReadOnlyChecker.CODE_NON_SELECT);
        assertDenied("INSERT INTO orders(id) VALUES (1)",
                ModelingSqlReadOnlyChecker.CODE_SIDE_EFFECT);
        assertDenied("DROP TABLE orders", ModelingSqlReadOnlyChecker.CODE_NON_SELECT);
        assertDenied("ALTER TABLE orders ADD COLUMN note VARCHAR(32)",
                ModelingSqlReadOnlyChecker.CODE_NON_SELECT);
        assertDenied("TRUNCATE TABLE orders", ModelingSqlReadOnlyChecker.CODE_NON_SELECT);
        assertDenied("VALUES (1)", ModelingSqlReadOnlyChecker.CODE_NON_SELECT);
        assertDenied("TABLE orders", ModelingSqlReadOnlyChecker.CODE_NON_SELECT);
    }

    /** 多语句与无法完整解析的 SQL 必须失败，避免只检查第一条语句。 */
    @Test
    void shouldRejectMultipleOrMalformedStatements() {
        assertDenied("SELECT * FROM orders; DELETE FROM orders",
                ModelingSqlReadOnlyChecker.CODE_MULTIPLE_STATEMENTS);
        assertDenied("SELECT * FROM", ModelingSqlReadOnlyChecker.CODE_PARSE_ERROR);
        assertDenied("   ", ModelingSqlReadOnlyChecker.CODE_EMPTY_SQL);
    }

    /** 锁定查询、SELECT INTO 与数据库写文件扩展必须按副作用语法阻断。 */
    @Test
    void shouldRejectSelectSideEffects() {
        assertDenied("SELECT * FROM orders FOR UPDATE",
                ModelingSqlReadOnlyChecker.CODE_SIDE_EFFECT);
        assertDenied("SELECT * FROM orders FOR SHARE", ModelingSqlReadOnlyChecker.CODE_SIDE_EFFECT);
        assertDenied("SELECT * INTO archived_orders FROM orders",
                ModelingSqlReadOnlyChecker.CODE_SIDE_EFFECT);
        assertDenied("SELECT * INTO OUTFILE '/tmp/orders.csv' FROM orders",
                ModelingSqlReadOnlyChecker.CODE_SIDE_EFFECT);
        assertDenied("SELECT * INTO DUMPFILE '/tmp/orders.bin' FROM orders",
                ModelingSqlReadOnlyChecker.CODE_SIDE_EFFECT);
        assertDenied("SELECT * FROM orders LOCK IN SHARE MODE",
                ModelingSqlReadOnlyChecker.CODE_SIDE_EFFECT);
    }

    /** PostgreSQL/MySQL 中会修改序列、会话或锁状态的 SELECT 函数必须全部阻断。 */
    @Test
    void shouldRejectDialectSideEffectFunctionsAndAssignments() {
        assertDenied("SELECT nextval('order_seq') LIMIT 20", "POSTGRESQL",
                ModelingSqlReadOnlyChecker.CODE_SIDE_EFFECT);
        assertDenied("SELECT setval('order_seq', 1) LIMIT 20", "POSTGRESQL",
                ModelingSqlReadOnlyChecker.CODE_SIDE_EFFECT);
        assertDenied("SELECT pg_advisory_lock(1) LIMIT 20", "POSTGRESQL",
                ModelingSqlReadOnlyChecker.CODE_SIDE_EFFECT);
        assertDenied("SELECT set_config('search_path', 'public', false) LIMIT 20", "POSTGRESQL",
                ModelingSqlReadOnlyChecker.CODE_SIDE_EFFECT);
        assertDenied("SELECT GET_LOCK('x', 10) LIMIT 20", "MYSQL",
                ModelingSqlReadOnlyChecker.CODE_SIDE_EFFECT);
        assertDenied("SELECT RELEASE_LOCK('x') LIMIT 20", "MYSQL",
                ModelingSqlReadOnlyChecker.CODE_SIDE_EFFECT);
        assertDenied("SELECT @x := 1 LIMIT 20", "MYSQL",
                ModelingSqlReadOnlyChecker.CODE_SIDE_EFFECT);
    }

    /** 副作用函数位于任意表达式或查询层级时仍必须由 AST 递归发现。 */
    @Test
    void shouldRejectNestedAndSchemaQualifiedSideEffects() {
        assertDenied("WITH c AS (SELECT pg_catalog.nextval('s') AS id) SELECT id FROM c LIMIT 20",
                "POSTGRESQL", ModelingSqlReadOnlyChecker.CODE_SIDE_EFFECT);
        assertDenied(
                "SELECT id FROM orders WHERE EXISTS "
                        + "(SELECT 1 WHERE pg_advisory_lock(1) IS NULL) LIMIT 20",
                "POSTGRESQL", ModelingSqlReadOnlyChecker.CODE_SIDE_EFFECT);
        assertDenied(
                "SELECT id FROM orders WHERE id = CASE WHEN set_config('x','y',false) = 'y' "
                        + "THEN 1 ELSE 2 END LIMIT 20",
                "POSTGRESQL", ModelingSqlReadOnlyChecker.CODE_SIDE_EFFECT);
        assertDenied("SELECT id FROM orders WHERE setval('s', 1) > 0 LIMIT 20", "POSTGRESQL",
                ModelingSqlReadOnlyChecker.CODE_SIDE_EFFECT);
        assertDenied("SELECT id FROM orders GROUP BY id HAVING nextval('s') > 0 LIMIT 20",
                "POSTGRESQL", ModelingSqlReadOnlyChecker.CODE_SIDE_EFFECT);
        assertDenied("SELECT a.id FROM a JOIN b ON pg_advisory_lock(a.id) = 1 LIMIT 20",
                "POSTGRESQL", ModelingSqlReadOnlyChecker.CODE_SIDE_EFFECT);
        assertDenied("SELECT id FROM orders ORDER BY set_config('x','y',false) LIMIT 20",
                "POSTGRESQL", ModelingSqlReadOnlyChecker.CODE_SIDE_EFFECT);
        assertDenied("SELECT id FROM orders UNION ALL SELECT setval('s', 1) LIMIT 20", "POSTGRESQL",
                ModelingSqlReadOnlyChecker.CODE_SIDE_EFFECT);
        assertDenied("SELECT COUNT(*) FROM orders GROUP BY pg_advisory_lock(id) LIMIT 20",
                "POSTGRESQL", ModelingSqlReadOnlyChecker.CODE_SIDE_EFFECT);
        assertDenied("SELECT value FROM set_config('x','y',false) AS value LIMIT 20", "POSTGRESQL",
                ModelingSqlReadOnlyChecker.CODE_SIDE_EFFECT);
    }

    /** 显式纯函数允许集应兼容常见聚合、空值、字符串和日期转换。 */
    @Test
    void shouldAllowExplicitPureFunctionsButRejectUnknownFunctions() {
        assertReadOnly("SELECT COUNT(*), SUM(amount), COALESCE(MAX(amount), 0) FROM orders",
                "POSTGRESQL");
        assertReadOnly("SELECT LOWER(name), DATE_TRUNC('day', created_at) FROM orders",
                "POSTGRESQL");
        assertReadOnly("SELECT IFNULL(SUM(amount), 0), DATE_FORMAT(created_at, '%Y-%m-%d') "
                + "FROM orders", "MYSQL");
        assertDenied("SELECT custom_mutating_udf(id) FROM orders LIMIT 20", "POSTGRESQL",
                ModelingSqlReadOnlyChecker.CODE_FUNCTION_NOT_PROVEN);
        assertDenied("SELECT private_schema.count(id) FROM orders LIMIT 20", "POSTGRESQL",
                ModelingSqlReadOnlyChecker.CODE_FUNCTION_NOT_PROVEN);
    }

    /** 函数名仅出现在字符串或注释中不能被危险函数策略误报。 */
    @Test
    void shouldIgnoreFunctionNamesInsideStringsAndComments() {
        assertReadOnly("SELECT 'nextval(1)' AS note /* pg_advisory_lock(1) */ FROM orders",
                "POSTGRESQL");
        assertReadOnly("SELECT 'GET_LOCK(1)' AS note -- RELEASE_LOCK(1)\nFROM orders", "MYSQL");
    }

    /** 验证一个安全查询并断言稳定通过代码。 */
    private void assertReadOnly(String sql) {
        assertReadOnly(sql, "H2");
    }

    /** 使用明确数据库方言验证一个安全查询。 */
    private void assertReadOnly(String sql, String databaseType) {
        ModelingSqlReadOnlyChecker.CheckResult result = checker.validate(sql, databaseType);
        assertTrue(result.readOnly(), result.message());
        assertEquals(ModelingSqlReadOnlyChecker.CODE_READ_ONLY, result.code());
    }

    /** 验证一个不安全查询并断言稳定拒绝代码。 */
    private void assertDenied(String sql, String code) {
        assertDenied(sql, "H2", code);
    }

    /** 使用明确数据库方言验证一个不安全查询。 */
    private void assertDenied(String sql, String databaseType, String code) {
        ModelingSqlReadOnlyChecker.CheckResult result = checker.validate(sql, databaseType);
        assertFalse(result.readOnly(), result.message());
        assertEquals(code, result.code());
    }
}
