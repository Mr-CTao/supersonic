package com.tencent.supersonic.headless.server.service;

import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.headless.api.pojo.DBColumn;
import com.tencent.supersonic.headless.api.pojo.enums.DataType;
import com.tencent.supersonic.headless.api.pojo.request.DatabaseReq;
import com.tencent.supersonic.headless.api.pojo.request.ModelBuildReq;
import com.tencent.supersonic.headless.api.pojo.request.SqlExecuteReq;
import com.tencent.supersonic.headless.api.pojo.response.DatabaseResp;
import com.tencent.supersonic.headless.api.pojo.response.SemanticQueryResp;
import com.tencent.supersonic.headless.server.pojo.DatabaseParameter;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * 数据源管理与受控查询服务。
 *
 * <p>
 * 职责说明：提供数据源 ACL、元数据读取、已有查询能力，以及阶段 3 专用的有限只读样例读取。 实现不得信任客户端上传的 Schema 或接受样例接口传入任意 SQL。
 * </p>
 */
public interface DatabaseService {

    SemanticQueryResp executeSql(String sql, DatabaseResp databaseResp);

    List<DatabaseResp> getDatabaseByType(DataType dataType);

    SemanticQueryResp executeSql(SqlExecuteReq sqlExecuteReq, User user);

    DatabaseResp getDatabase(Long id, User user);

    DatabaseResp getDatabase(Long id);

    Map<String, List<DatabaseParameter>> getDatabaseParameters(User user);

    boolean testConnect(DatabaseReq databaseReq, User user);

    DatabaseResp createOrUpdateDatabase(DatabaseReq databaseReq, User user);

    List<DatabaseResp> getDatabaseList(User user);

    void deleteDatabase(Long databaseId, User user);

    List<String> getCatalogs(Long id) throws SQLException;

    List<String> getDbNames(Long id, String catalog) throws SQLException;

    List<String> getTables(Long id, String catalog, String db) throws SQLException;

    Map<String, List<DBColumn>> getDbColumns(ModelBuildReq modelBuildReq) throws SQLException;

    List<DBColumn> getColumns(Long id, String catalog, String db, String table) throws SQLException;

    List<DBColumn> getColumns(Long id, String sql) throws SQLException;

    /**
     * 对具有数据源权限的用户读取最多指定行数的表样例。
     *
     * @param id 数据源 ID。
     * @param catalog catalog，可为空。
     * @param db schema/database，可为空。
     * @param table 服务端元数据确认过的表名。
     * @param maxRows 最大行数。
     * @param timeoutSeconds 超时秒数。
     * @param user 当前用户，用于复核 ACL。
     * @return 样例行；LOB/二进制列不返回。
     * @throws SQLException JDBC 查询失败时抛出，由草稿上下文构建器降级处理。
     */
    List<Map<String, Object>> sampleRows(Long id, String catalog, String db, String table,
            int maxRows, int timeoutSeconds, User user) throws SQLException;
}
