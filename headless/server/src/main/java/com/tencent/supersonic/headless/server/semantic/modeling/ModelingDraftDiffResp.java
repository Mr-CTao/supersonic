package com.tencent.supersonic.headless.server.semantic.modeling;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 语义建模草稿版本差异响应。
 *
 * <p>
 * 职责说明：绑定草稿及起止版本，返回汇总文案、最多 200 条结构化差异和截断标记。DTO 在单次请求内创建后返回， 不在并发请求间共享；服务层会传入不可变 items
 * 快照，避免比较完成后被后台任务继续修改。
 * </p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelingDraftDiffResp {

    /** 草稿 ID。 */
    private Long draftId;

    /** 起始版本号。 */
    private Integer fromVersionNo;

    /** 目标版本号。 */
    private Integer toVersionNo;

    /** 新增、删除和修改数量的简短汇总。 */
    private String summary;

    /** 结构化差异项，最多 200 条。 */
    private List<ModelingDraftDiffItem> items;

    /** 是否因差异数量超过上限而截断 items。 */
    private boolean truncated;
}
