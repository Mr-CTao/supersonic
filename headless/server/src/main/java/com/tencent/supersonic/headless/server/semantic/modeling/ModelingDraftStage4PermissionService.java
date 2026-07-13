package com.tencent.supersonic.headless.server.semantic.modeling;

import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.headless.api.pojo.response.DatabaseResp;
import com.tencent.supersonic.headless.api.pojo.response.DomainResp;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticModelingDraftDO;
import com.tencent.supersonic.headless.server.persistence.mapper.SemanticModelingDraftMapper;
import com.tencent.supersonic.headless.server.service.DatabaseService;
import com.tencent.supersonic.headless.server.service.DomainService;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * 语义建模草稿统一写操作权限服务。
 *
 * <p>
 * 职责说明：统一保护人工保存、重新生成、AI 修订、验证和提交审批入口。普通读取权限、公共数据源可见性
 * 或草稿创建者身份都不能单独授予写权限；非超级管理员必须是数据源管理员，并在草稿绑定主题域时同时拥有
 * 整个主题域的编辑权限。仅管理域内某个模型不能横向扩展为域级草稿写权限。该服务只执行只读权限判断， 不持有共享可变状态，因此无需额外并发锁。
 * </p>
 */
@Service
@Slf4j
public class ModelingDraftStage4PermissionService {

    private final SemanticModelingDraftMapper draftMapper;
    private final DatabaseService databaseService;
    private final DomainService domainService;

    /**
     * 创建阶段 4 权限服务。
     *
     * @param draftMapper 草稿 Mapper。
     * @param databaseService 数据源 ACL 服务。
     * @param domainService 主题域权限服务。
     */
    public ModelingDraftStage4PermissionService(SemanticModelingDraftMapper draftMapper,
            DatabaseService databaseService, DomainService domainService) {
        this.draftMapper = draftMapper;
        this.databaseService = databaseService;
        this.domainService = domainService;
    }

    /**
     * 查询草稿并校验统一写管理权限。
     *
     * <p>
     * 调用示例：{@code permissionService.requireManageable(draftId, user)}。本方法不会把无权访问的
     * 数据源、主题域或草稿创建者信息返回给客户端。
     * </p>
     *
     * @param draftId 草稿 ID。
     * @param user 当前登录用户。
     * @return 已通过管理权限校验的草稿记录。
     * @throws ModelingDraftException 草稿不存在或操作者无管理权限时抛出。
     */
    public SemanticModelingDraftDO requireManageable(Long draftId, User user) {
        SemanticModelingDraftDO draft = draftMapper.selectById(draftId);
        if (draft == null) {
            throw new ModelingDraftException(HttpStatus.NOT_FOUND,
                    ModelingDraftConstants.ERROR_NOT_FOUND, "语义建模草稿不存在");
        }
        if (!canManage(draft, user)) {
            throw denied();
        }
        return draft;
    }

    /**
     * 查询草稿并校验基础数据源读取权限。
     *
     * <p>
     * 仅供版本差异、验证报告等纯读取能力复用；AI 修订、保存、执行验证和提交审批仍必须调用
     * {@link #requireManageable(Long, User)}，不能用本方法绕过管理员门禁。
     * </p>
     *
     * @param draftId 草稿 ID。
     * @param user 当前登录用户。
     * @return 已通过数据源读取 ACL 的草稿。
     * @throws ModelingDraftException 草稿不存在或当前用户无读取权限时抛出。
     */
    public SemanticModelingDraftDO requireReadable(Long draftId, User user) {
        SemanticModelingDraftDO draft = draftMapper.selectById(draftId);
        if (draft == null) {
            throw new ModelingDraftException(HttpStatus.NOT_FOUND,
                    ModelingDraftConstants.ERROR_NOT_FOUND, "语义建模草稿不存在");
        }
        if (user == null || StringUtils.isBlank(user.getName())) {
            throw denied();
        }
        requireDataSourceAccess(draft, user);
        return draft;
    }

    /**
     * 判断已可见草稿对当前用户是否开放阶段 4 写操作。
     *
     * <p>
     * 详情接口使用该结果向前端明确返回只读能力；权限服务异常时安全退化为 {@code false}，不会把 viewer/public 误标为管理员。方法只读取
     * ACL，不修改草稿或正式语义资产。
     * </p>
     *
     * @param draft 已通过基础可见性校验的草稿。
     * @param user 当前登录用户。
     * @return 同时具备数据源和可选主题域管理权限时返回 true。
     */
    public boolean canManage(SemanticModelingDraftDO draft, User user) {
        if (draft == null || user == null || StringUtils.isBlank(user.getName())) {
            return false;
        }
        try {
            DatabaseResp database = requireDataSourceAccess(draft, user);
            if (!user.isSuperAdmin() && !hasDataSourceAdminPermission(database, user)) {
                return false;
            }
            return user.isSuperAdmin() || draft.getDomainId() == null
                    || hasDomainAdminPermission(draft.getDomainId(), user);
        } catch (RuntimeException exception) {
            // 仅记录草稿 ID 和异常类型，避免把数据源 ACL 或内部异常消息带入日志。
            log.debug("semantic modeling manage capability check failed: draftId={}, type={}",
                    draft.getId(), exception.getClass().getName());
            return false;
        }
    }

    /** 先复核基础数据源 ACL，并返回用于进一步判断 ADMIN 权限的服务端记录。 */
    private DatabaseResp requireDataSourceAccess(SemanticModelingDraftDO draft, User user) {
        try {
            DatabaseResp database = databaseService.getDatabase(draft.getDataSourceId(), user);
            if (database == null) {
                throw new IllegalArgumentException("Database not found");
            }
            return database;
        } catch (RuntimeException exception) {
            throw denied();
        }
    }

    /** 判断用户是否为数据源创建者或管理员；viewer/public 只具备读取资格。 */
    private boolean hasDataSourceAdminPermission(DatabaseResp database, User user) {
        String userName = user.getName();
        boolean creator = StringUtils.equalsIgnoreCase(database.getCreatedBy(), userName);
        boolean administrator = Objects.requireNonNullElse(database.getAdmins(), List.<String>of())
                .stream().filter(StringUtils::isNotBlank)
                .anyMatch(name -> StringUtils.equalsIgnoreCase(name, userName));
        return creator || administrator;
    }

    /** 判断用户是否拥有整个主题域编辑权限；hasModel 只表示域内局部模型可见，不能用于写授权。 */
    private boolean hasDomainAdminPermission(Long domainId, User user) {
        List<DomainResp> domains = domainService.getDomainListWithAdminAuth(user);
        return domains != null && domains.stream().filter(Objects::nonNull).anyMatch(
                domain -> Objects.equals(domainId, domain.getId()) && domain.isHasEditPermission());
    }

    /** 构造统一且不泄露内部授权对象的权限异常。 */
    private ModelingDraftException denied() {
        return new ModelingDraftException(HttpStatus.FORBIDDEN,
                ModelingDraftConstants.ERROR_ACCESS_DENIED, "无权执行该草稿管理操作");
    }
}
