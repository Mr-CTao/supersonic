package com.tencent.supersonic.headless.server.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticModelingValidationReportDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.Date;

/**
 * AI 语义建模草稿验证报告 Mapper。
 *
 * <p>
 * 职责说明：为阶段 4 验证报告提供 MyBatis-Plus 基础新增、条件更新和只读查询能力。业务层只能写入隔离的验证报告表，不得通过本 Mapper
 * 修改正式模型、维度、指标或术语。并发说明：单草稿运行任务互斥由数据库唯一键 {@code (draft_id, active_marker)} 保证，不能以 JVM 本地锁替代。
 * </p>
 */
@Mapper
public interface SemanticModelingValidationReportMapper
        extends BaseMapper<SemanticModelingValidationReportDO> {

    /**
     * 读取数据库实例时间，避免多 JVM 时钟偏移导致验证租约误恢复。
     *
     * @return 数据库当前时间。
     */
    @Select("SELECT CURRENT_TIMESTAMP")
    Date selectCurrentTimestamp();
}
