# AI 语义建模菜单视觉 QA

- source visual truth path: `/var/folders/yc/pjkcftks6cxf8hx1qqhj_dmc0000gn/T/codex-clipboard-39460526-7008-407d-8655-d4d11e7eec23.png`
- implementation screenshot path: `/Users/bu_jiangjiu/.codex/visualizations/2026/07/22/019f893e-64be-7363-8ad2-456d1854296e/ai-semantic-modeling-gap-pool-final.png`
- full-view comparison evidence: `/Users/bu_jiangjiu/.codex/visualizations/2026/07/22/019f893e-64be-7363-8ad2-456d1854296e/design-qa-full-comparison.png`
- focused navigation comparison evidence: `/Users/bu_jiangjiu/.codex/visualizations/2026/07/22/019f893e-64be-7363-8ad2-456d1854296e/design-qa-navigation-comparison.png`
- viewport: `1719 x 773` CSS px, `devicePixelRatio = 1`
- source pixels: `1719 x 771`
- implementation pixels: `1686 x 758`（应用内浏览器截图接口裁掉滚动条占位；页面内读取的 CSS viewport 仍为 `1719 x 773`）
- density normalization: 全页对照把两张截图等比例放入相同宽度容器；聚焦对照以 `1719px` 逻辑宽度对齐左上角，implementation 仅做约 `1.96%` 等比补偿，不据此判断字体绝对像素差异
- state: 参考图为“出入库预测”左侧导航展开且“AI 语义建模”旧下拉展开；实现图为“AI 语义建模”左侧导航展开、语义缺口池选中，顶部只保留一级入口

## Findings

- 未发现可执行的 P0、P1 或 P2 差异。
- 顶部“AI 语义建模”已经从带子菜单的悬浮入口变为普通一级链接，目标中的三个子入口已迁移到固定左侧导航。
- 左侧导航复用出入库预测的同一工作台组件，宽度、64px 菜单行高、选中背景、左侧主色标记、分隔线、页头节奏和收起入口一致。
- 参考图与实现图中的业务内容、菜单图标和文字不同，属于模块语义差异；实现使用 Ant Design 现有图标库，没有新增或近似绘制图形资产。

## Required Fidelity Surfaces

- Fonts and typography: 沿用项目现有 Ant Design 字体栈、标题层级、字重和单行截断规则；参考图的浏览器缩放比例更大，因此不把绝对物理像素差异判定为回归。
- Spacing and layout rhythm: 左侧导航、紧凑页头、内容内边距、选中行高与出入库预测共用同一组件和同一 LESS 令牌；在 `1719 x 773` CSS 视口未出现遮挡或持久控件溢出。
- Colors and visual tokens: 复用预测模块的 `#296df3` 主色、`#fafafb` 工作台背景、白色导航背景和 `#edf0f5` 分隔线。
- Image quality and asset fidelity: 页面仅使用已有品牌 Logo 与 Ant Design 矢量图标；没有新增位图、占位图、CSS 图形或手工 SVG。
- Copy and content: “语义缺口池 / 建模草稿 / 发布审计”与原路由文案一致；新增页头辅助说明分别对应缺口沉淀、草稿校准验证和发布治理职责。

## Interaction And Runtime Checks

- 验证 `/webapp/ai-semantic-modeling/gaps`、`/drafts`、`/releases` 三个子页面均可由左侧菜单切换，URL、页头标题和选中态同步。
- 验证发布审计入口在当前系统管理员账号可见；代码层仍由 Umi `SYSTEM_ADMIN` access 与路由权限双重约束。
- 验证收起后导航 `aria-hidden=true` 且展开按钮出现；重新展开后导航宽度恢复为 `216px`，`aria-expanded=true`。
- 验证顶部 AI 菜单元素为普通 `ant-menu-item`，`aria-haspopup` 为空、子菜单数量为 `0`。
- 验证共享组件改造后的出入库预测仍显示“预测看板 / 数据接入 / 运行中心”，预测看板选中且导航宽度为 `216px`。
- 浏览器控制台未出现本次导航改造导致的未捕获错误；仍存在原页面的 Ant Design deprecated API 与未连接 `useForm` 基线警告，不属于本次菜单范围。

## Comparison History

- Pass 1: 全页与导航聚焦对照未发现 P0/P1/P2；无需视觉修复迭代。

## Follow-up Polish

- 无阻断项；参考图中的红色标注和不同浏览器缩放不属于产品 UI。

final result: passed
