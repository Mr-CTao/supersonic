package com.tencent.supersonic.headless.server.semantic.modeling;

import com.tencent.supersonic.common.pojo.User;
import com.tencent.supersonic.headless.api.pojo.response.DatabaseResp;
import com.tencent.supersonic.headless.api.pojo.response.DomainResp;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticModelingDraftDO;
import com.tencent.supersonic.headless.server.persistence.mapper.SemanticModelingDraftMapper;
import com.tencent.supersonic.headless.server.service.DatabaseService;
import com.tencent.supersonic.headless.server.service.DomainService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 语义建模草稿统一写权限测试。
 *
 * <p>
 * 职责说明：验证公共或 viewer 数据源访问、草稿创建者身份都不能替代 ADMIN 写权限，同时允许非创建者的 数据源管理员管理未绑定主题域的草稿。测试只使用
 * Mockito，不访问真实权限表或数据源。
 * </p>
 */
class ModelingDraftStage4PermissionServiceTest {

    private SemanticModelingDraftMapper draftMapper;
    private DatabaseService databaseService;
    private DomainService domainService;
    private ModelingDraftStage4PermissionService permissionService;

    /** 初始化隔离的 Mapper 与权限服务。 */
    @BeforeEach
    void setUp() {
        draftMapper = mock(SemanticModelingDraftMapper.class);
        databaseService = mock(DatabaseService.class);
        domainService = mock(DomainService.class);
        permissionService = new ModelingDraftStage4PermissionService(draftMapper, databaseService,
                domainService);
    }

    /** 即使用户创建了草稿且数据源为 public/viewer，也不能获得草稿写权限。 */
    @Test
    void shouldRejectCreatorWithOnlyPublicViewerAccess() {
        User viewer = User.get(2L, "viewer");
        SemanticModelingDraftDO draft = draft("viewer");
        DatabaseResp database = database("owner", List.of(), List.of("viewer"), 1);
        when(draftMapper.selectById(1L)).thenReturn(draft);
        when(databaseService.getDatabase(9L, viewer)).thenReturn(database);

        assertThat(permissionService.requireReadable(1L, viewer)).isSameAs(draft);
        assertThat(permissionService.canManage(draft, viewer)).isFalse();
        assertThatThrownBy(() -> permissionService.requireManageable(1L, viewer))
                .isInstanceOfSatisfying(ModelingDraftException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.FORBIDDEN);
                    assertThat(exception.getErrorCode())
                            .isEqualTo(ModelingDraftConstants.ERROR_ACCESS_DENIED);
                });
    }

    /** 数据源管理员可以管理其他人创建且未绑定主题域的草稿。 */
    @Test
    void shouldAllowDataSourceAdminWithoutCreatorIdentity() {
        User administrator = User.get(3L, "data-admin");
        SemanticModelingDraftDO draft = draft("draft-owner");
        DatabaseResp database = database("owner", List.of("DATA-ADMIN"), List.of(), 0);
        when(draftMapper.selectById(1L)).thenReturn(draft);
        when(databaseService.getDatabase(9L, administrator)).thenReturn(database);

        assertThat(permissionService.canManage(draft, administrator)).isTrue();
        assertThat(permissionService.requireManageable(1L, administrator)).isSameAs(draft);
    }

    /** 超级管理员在通过基础数据源防枚举检查后可管理已绑定主题域草稿。 */
    @Test
    void shouldAllowSuperAdministrator() {
        User superAdmin = User.getDefaultUser();
        SemanticModelingDraftDO draft = draft("owner");
        draft.setDomainId(7L);
        when(databaseService.getDatabase(9L, superAdmin))
                .thenReturn(database("owner", List.of(), List.of(), 0));

        assertThat(permissionService.canManage(draft, superAdmin)).isTrue();
    }

    /** 数据源管理员同时拥有整个主题域编辑权限时才可管理绑定域的草稿。 */
    @Test
    void shouldAllowDataSourceAdminWithDomainEditPermission() {
        User administrator = User.get(3L, "data-admin");
        SemanticModelingDraftDO draft = draft("owner");
        draft.setDomainId(7L);
        when(databaseService.getDatabase(9L, administrator))
                .thenReturn(database("owner", List.of("data-admin"), List.of(), 0));
        when(domainService.getDomainListWithAdminAuth(administrator))
                .thenReturn(List.of(domain(7L, true, true)));

        assertThat(permissionService.canManage(draft, administrator)).isTrue();
    }

    /** 仅管理同域某个模型不能横向获得整个主题域内其他草稿的写权限。 */
    @Test
    void shouldRejectDataSourceAdminWithOnlyModelPermission() {
        User administrator = User.get(3L, "data-admin");
        SemanticModelingDraftDO draft = draft("other-model-owner");
        draft.setDomainId(7L);
        when(draftMapper.selectById(1L)).thenReturn(draft);
        when(databaseService.getDatabase(9L, administrator))
                .thenReturn(database("owner", List.of("data-admin"), List.of(), 0));
        when(domainService.getDomainListWithAdminAuth(administrator))
                .thenReturn(List.of(domain(7L, false, true)));

        assertThat(permissionService.canManage(draft, administrator)).isFalse();
        assertThatThrownBy(() -> permissionService.requireManageable(1L, administrator))
                .isInstanceOfSatisfying(ModelingDraftException.class,
                        exception -> assertThat(exception.getStatus())
                                .isEqualTo(HttpStatus.FORBIDDEN));
    }

    /** 仅主题域管理员但不是数据源管理员时仍只能按数据源 ACL 读取，不能写。 */
    @Test
    void shouldRejectDomainAdminWithoutDataSourceAdminPermission() {
        User domainAdmin = User.get(4L, "domain-admin");
        SemanticModelingDraftDO draft = draft("owner");
        draft.setDomainId(7L);
        when(draftMapper.selectById(1L)).thenReturn(draft);
        when(databaseService.getDatabase(9L, domainAdmin))
                .thenReturn(database("owner", List.of(), List.of("domain-admin"), 0));
        when(domainService.getDomainListWithAdminAuth(domainAdmin))
                .thenReturn(List.of(domain(7L, true, false)));

        assertThat(permissionService.requireReadable(1L, domainAdmin)).isSameAs(draft);
        assertThat(permissionService.canManage(draft, domainAdmin)).isFalse();
    }

    /** 构造未绑定主题域的草稿。 */
    private SemanticModelingDraftDO draft(String createdBy) {
        SemanticModelingDraftDO draft = new SemanticModelingDraftDO();
        draft.setId(1L);
        draft.setDataSourceId(9L);
        draft.setCreatedBy(createdBy);
        return draft;
    }

    /** 构造带服务端管理员、viewer 和 public 标记的数据源。 */
    private DatabaseResp database(String createdBy, List<String> admins, List<String> viewers,
            int isOpen) {
        DatabaseResp database = DatabaseResp.builder().id(9L).admins(admins).viewers(viewers)
                .isOpen(isOpen).build();
        database.setCreatedBy(createdBy);
        return database;
    }

    /** 构造服务端主题域授权响应。 */
    private DomainResp domain(Long id, boolean hasEditPermission, boolean hasModel) {
        DomainResp domain = new DomainResp();
        domain.setId(id);
        domain.setHasEditPermission(hasEditPermission);
        domain.setHasModel(hasModel);
        return domain;
    }
}
