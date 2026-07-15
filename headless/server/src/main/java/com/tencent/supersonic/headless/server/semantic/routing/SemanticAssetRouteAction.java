package com.tencent.supersonic.headless.server.semantic.routing;

/**
 * 语义资产路由动作。
 *
 * <p>
 * 职责：作为服务端策略、持久化和 API 的唯一动作枚举，前端只能展示服务端返回值，不能根据 分数自行推断。枚举不可变且无共享可变状态，天然线程安全。
 * </p>
 */
public enum SemanticAssetRouteAction {
    REUSE_EXISTING("复用已有资产"),
    EXTEND_EXISTING("增强已有资产"),
    CREATE_NEW("新建语义资产"),
    NEEDS_CLARIFICATION("需要补充业务口径");

    private final String label;

    SemanticAssetRouteAction(String label) {
        this.label = label;
    }

    /**
     * 返回面向管理员的中文动作名称。
     *
     * @return 稳定中文标签。
     */
    public String getLabel() {
        return label;
    }
}
