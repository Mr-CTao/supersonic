package com.tencent.supersonic.headless.server.semantic.modeling;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 人工保存 AI 语义建模草稿请求。
 *
 * <p>
 * 职责说明：携带客户端读取到的乐观锁版本和完整结构化草稿。为兼容独立前端演进，既接受对象形式 {@code currentDraft}，也接受字符串形式
 * {@code draftJson}，但两者必须至少提供一个。并发说明：数据库 条件更新保证旧 lockVersion 无法静默覆盖新版本。
 * </p>
 */
@Data
public class ModelingDraftSaveReq {

    @NotNull
    private Integer lockVersion;

    private JsonNode currentDraft;

    private String draftJson;

    @Size(max = 512)
    private String changeSummary;
}
