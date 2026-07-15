package com.tencent.supersonic.headless.server.semantic.diagnostic;

import com.tencent.supersonic.headless.api.pojo.SqlVariable;
import com.tencent.supersonic.headless.api.pojo.request.ModelReq;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * 模型 SQL 双重校验请求。
 *
 * <p>
 * 职责说明：承载 SQL 编辑阶段的数据源执行与语义编译输入；可选 model 字段用于保存/发布前 完整表达式检查。请求按调用创建，不跨线程修改。
 * </p>
 */
@Data
public class SemanticModelValidationReq {
    private Long databaseId;
    private Long modelId;
    private String modelName;
    private Long dataSetId;
    private String sql;
    private List<SqlVariable> sqlVariables = new ArrayList<>();
    private ModelReq model;
    private boolean executeSource = true;
}
