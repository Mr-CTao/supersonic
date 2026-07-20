package com.tencent.supersonic.forecast.server.service;

import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.common.pojo.exception.InvalidArgumentException;
import com.tencent.supersonic.common.pojo.exception.InvalidPermissionException;
import com.tencent.supersonic.headless.api.pojo.response.DatabaseResp;
import com.tencent.supersonic.headless.server.service.DatabaseService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * Forecast 对现有数据源 ACL 的唯一适配层。
 *
 * <p>
 * 查询继承源数据库 viewer/admin 权限；配置、发布和任务操作要求数据源管理员或系统管理员。 使用 {@link ObjectProvider} 是为了让无 Web/认证 Bean
 * 的独立 Worker 仍能启动，Worker 不调用本类。
 * </p>
 */
@Service
public class ForecastAccessService {

    private final ObjectProvider<DatabaseService> databaseServiceProvider;

    /**
     * 创建 ACL 服务。
     *
     * @param databaseServiceProvider 现有数据库服务延迟提供者。
     */
    public ForecastAccessService(ObjectProvider<DatabaseService> databaseServiceProvider) {
        this.databaseServiceProvider = databaseServiceProvider;
    }

    /**
     * 校验数据源查看权限。
     *
     * @param databaseId 数据源 ID。
     * @param user 当前用户。
     * @return 已通过 ACL 的数据库描述。
     * @throws InvalidPermissionException 无查看权限或 API 未加载。
     */
    public DatabaseResp requireViewer(Long databaseId, User user) {
        requireUser(user);
        return requireDatabaseService().getDatabase(databaseId, user);
    }

    /**
     * 校验数据源管理权限。
     *
     * @param databaseId 数据源 ID。
     * @param user 当前用户。
     * @return 数据库描述。
     * @throws InvalidPermissionException 当前用户不是管理员。
     */
    public DatabaseResp requireAdmin(Long databaseId, User user) {
        requireUser(user);
        DatabaseResp database = requireDatabaseService().getDatabase(databaseId, user);
        List<String> admins = database.getAdmins() == null ? List.of() : database.getAdmins();
        boolean admin = user.isSuperAdmin() || admins.contains(user.getName())
                || Objects.equals(database.getCreatedBy(), user.getName());
        if (!admin) {
            throw new InvalidPermissionException("预测配置和任务操作要求数据源管理员权限");
        }
        return database;
    }

    /**
     * 一次读取当前用户可见数据源，供 Profile 分页权限过滤，避免逐 Profile 调用造成 N+1。
     *
     * @param user 当前用户。
     * @return 可见数据源列表，不含密码。
     */
    public List<DatabaseResp> visibleDatabases(User user) {
        requireUser(user);
        return requireDatabaseService().getDatabaseList(user);
    }

    /** 确保认证用户存在。 */
    private void requireUser(User user) {
        if (user == null || user.getName() == null || user.getName().isBlank()) {
            throw new InvalidPermissionException("用户未登录");
        }
    }

    /** Standalone 必须提供现有 DatabaseService；Worker 不调用此方法。 */
    private DatabaseService requireDatabaseService() {
        DatabaseService service = databaseServiceProvider.getIfAvailable();
        if (service == null) {
            throw new InvalidArgumentException("当前进程未启用数据库权限服务");
        }
        return service;
    }
}
