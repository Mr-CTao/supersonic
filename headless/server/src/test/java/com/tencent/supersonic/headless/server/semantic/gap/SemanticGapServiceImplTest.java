package com.tencent.supersonic.headless.server.semantic.gap;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.tencent.supersonic.headless.server.persistence.dataobject.SemanticGapDO;
import com.tencent.supersonic.headless.server.persistence.mapper.SemanticGapMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Date;
import java.util.concurrent.ThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 语义缺口采集与阶段 3 状态流转的并发回归测试。
 *
 * <p>
 * 职责说明：验证重复采集只更新统计/诊断字段，不把旧实体中的状态写回数据库；同时验证管理员忽略 使用条件更新，在阶段 3 已改变状态时拒绝覆盖。测试不启动异步线程或连接数据库。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class SemanticGapServiceImplTest {

    private static final Long GAP_ID = 21L;

    @Mock
    private SemanticGapMapper semanticGapMapper;

    @Mock
    private ThreadPoolExecutor captureExecutor;

    private SemanticGapServiceImpl service;

    /** 注册 Lambda 列缓存，保证纯 Mockito 测试能构造与生产一致的更新条件。 */
    @BeforeAll
    static void initializeMyBatisLambdaMetadata() {
        MapperBuilderAssistant assistant =
                new MapperBuilderAssistant(new MybatisConfiguration(), "semantic-gap-service-test");
        TableInfoHelper.initTableInfo(assistant, SemanticGapDO.class);
    }

    /** 为每个用例创建无共享状态的服务实例。 */
    @BeforeEach
    void setUp() {
        service = new SemanticGapServiceImpl(semanticGapMapper, captureExecutor);
    }

    /** 重复采集不得把读取时的 DRAFTING 状态放入 UPDATE SET。 */
    @Test
    void shouldPreserveModelingStatusWhenCapturingRepeatedGap() {
        SemanticGapDO current = newGap(SemanticGapStatus.DRAFTING);
        current.setOccurrenceCount(1);
        current.setNegativeFeedbackCount(0);
        current.setPriorityScore(10);
        current.setLastSeenAt(new Date());
        current.setRecentQuestions("查询库存");
        when(semanticGapMapper.selectOne(any())).thenReturn(current);
        when(semanticGapMapper.update(isNull(), any())).thenReturn(1);
        when(semanticGapMapper.selectById(GAP_ID)).thenReturn(current);

        SemanticGapEventReq event = new SemanticGapEventReq();
        event.setQuestion("查询库存");
        event.setFailureType(SemanticGapFailureType.NO_SELECTED_PARSE);
        event.setUserName("tester");

        SemanticGapDO result = service.capture(event);

        assertEquals(current, result);
        ArgumentCaptor<LambdaUpdateWrapper<SemanticGapDO>> updateCaptor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(semanticGapMapper).update(isNull(), updateCaptor.capture());
        String sqlSet = updateCaptor.getValue().getSqlSet().toLowerCase();
        assertFalse(sqlSet.contains("status"), sqlSet);
        assertFalse(updateCaptor.getValue().getParamNameValuePairs()
                .containsValue(SemanticGapStatus.DRAFTING.name()));
    }

    /** 忽略动作在条件更新未命中时必须报告并发状态变化。 */
    @Test
    void shouldRejectIgnoreWhenStatusChangesBeforeUpdate() {
        SemanticGapDO current = newGap(SemanticGapStatus.PENDING_ANALYSIS);
        when(semanticGapMapper.selectById(GAP_ID)).thenReturn(current);
        when(semanticGapMapper.update(isNull(), any())).thenReturn(0);

        SemanticGapActionReq request = new SemanticGapActionReq();
        request.setReason("暂不处理");

        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> service.ignore(GAP_ID, request, "admin"));

        assertEquals("semantic gap status changed, please reload", exception.getMessage());
    }

    /** 构造具有指定治理状态的缺口。 */
    private SemanticGapDO newGap(SemanticGapStatus status) {
        SemanticGapDO gap = new SemanticGapDO();
        gap.setId(GAP_ID);
        gap.setQuestion("查询库存");
        gap.setNormalizedQuestion("查询库存");
        gap.setFailureType(SemanticGapFailureType.NO_SELECTED_PARSE.name());
        gap.setStatus(status.name());
        gap.setCreatedAt(new Date());
        return gap;
    }
}
