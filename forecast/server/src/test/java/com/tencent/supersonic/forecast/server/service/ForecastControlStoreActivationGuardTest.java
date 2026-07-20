package com.tencent.supersonic.forecast.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.supersonic.common.pojo.exception.InvalidArgumentException;
import com.tencent.supersonic.forecast.api.enums.ForecastJobStatus;
import com.tencent.supersonic.forecast.api.enums.ForecastJobType;
import com.tencent.supersonic.forecast.api.request.ForecastJobReq;
import com.tencent.supersonic.forecast.server.config.ForecastProperties;
import com.tencent.supersonic.forecast.server.persistence.dataobject.ForecastJobDO;
import com.tencent.supersonic.forecast.server.persistence.dataobject.ForecastStreamDO;
import com.tencent.supersonic.forecast.server.persistence.mapper.ForecastJobMapper;
import com.tencent.supersonic.forecast.server.persistence.mapper.ForecastMappingMapper;
import com.tencent.supersonic.forecast.server.persistence.mapper.ForecastProfileMapper;
import com.tencent.supersonic.forecast.server.persistence.mapper.ForecastResourceLeaseMapper;
import com.tencent.supersonic.forecast.server.persistence.mapper.ForecastStreamMapper;
import com.tencent.supersonic.forecast.server.persistence.mapper.ForecastWatermarkMapper;
import com.tencent.supersonic.forecast.server.persistence.mapper.ForecastWorkerNodeMapper;
import com.tencent.supersonic.forecast.server.util.ForecastJson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Forecast 首次同步业务并发保护单元测试。
 *
 * <p>
 * 这里验证应用层的友好复用/冲突语义；数据库唯一索引的真实竞争裁决由 H2 迁移测试覆盖。
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class ForecastControlStoreActivationGuardTest {

    @Mock
    private ForecastProfileMapper profileMapper;
    @Mock
    private ForecastStreamMapper streamMapper;
    @Mock
    private ForecastMappingMapper mappingMapper;
    @Mock
    private ForecastJobMapper jobMapper;
    @Mock
    private ForecastWatermarkMapper watermarkMapper;
    @Mock
    private ForecastWorkerNodeMapper workerNodeMapper;
    @Mock
    private ForecastResourceLeaseMapper resourceLeaseMapper;

    private ForecastControlStore controlStore;

    /** 构造只包含控制面依赖的测试服务。 */
    @BeforeEach
    void setUp() {
        ForecastProperties properties = new ForecastProperties();
        ForecastJson json = new ForecastJson(new ObjectMapper());
        controlStore =
                new ForecastControlStore(profileMapper, streamMapper, mappingMapper, jobMapper,
                        watermarkMapper, workerNodeMapper, resourceLeaseMapper, properties, json);
    }

    @Test
    @DisplayName("同一映射重复激活会复用已有非终态任务")
    void shouldReuseActiveJobForSameMapping() {
        ForecastJobReq request = initialSyncRequest(7L, 11L);
        ForecastJobDO existing = activeJob(91L, 7L, 11L);
        when(streamMapper.selectById(7L)).thenReturn(stream(7L, null));
        when(jobMapper.selectActiveInitialSync(7L)).thenReturn(existing);

        ForecastJobDO result = controlStore.createJob(request, "request-a", "tester");

        assertSame(existing, result);
        verify(jobMapper, never()).insert(any(ForecastJobDO.class));
    }

    @Test
    @DisplayName("不同映射不能占用同一数据流首次同步槽")
    void shouldRejectDifferentMappingWhileActivationIsPending() {
        ForecastJobReq request = initialSyncRequest(7L, 12L);
        when(streamMapper.selectById(7L)).thenReturn(stream(7L, null));
        when(jobMapper.selectActiveInitialSync(7L)).thenReturn(activeJob(91L, 7L, 11L));

        InvalidArgumentException exception = assertThrows(InvalidArgumentException.class,
                () -> controlStore.createJob(request, "request-b", "tester"));

        assertEquals("数据流已有首次同步任务 #91（状态 QUEUED），请等待完成或取消后再激活其他映射", exception.getMessage());
        verify(jobMapper, never()).insert(any(ForecastJobDO.class));
    }

    @Test
    @DisplayName("并发插入由唯一键裁决后仍按同映射语义返回胜出任务")
    void shouldResolveDatabaseConcurrencyWinner() {
        ForecastJobReq request = initialSyncRequest(7L, 11L);
        ForecastJobDO winner = activeJob(92L, 7L, 11L);
        when(streamMapper.selectById(7L)).thenReturn(stream(7L, null));
        when(jobMapper.selectActiveInitialSync(7L)).thenReturn(null);
        when(jobMapper.selectDatabaseTime()).thenReturn(new Date());
        when(jobMapper.insert(any(ForecastJobDO.class)))
                .thenThrow(new DuplicateKeyException("concurrent insert"));
        when(jobMapper.selectActiveByConcurrencyKey("INITIAL_SYNC:STREAM:7")).thenReturn(winner);

        ForecastJobDO result = controlStore.createJob(request, "request-c", "tester");

        assertSame(winner, result);
    }

    @Test
    @DisplayName("已经激活的映射不能再次创建首次同步")
    void shouldRejectMappingThatIsAlreadyActive() {
        ForecastJobReq request = initialSyncRequest(7L, 11L);
        when(streamMapper.selectById(7L)).thenReturn(stream(7L, 11L));

        InvalidArgumentException exception = assertThrows(InvalidArgumentException.class,
                () -> controlStore.createJob(request, "request-d", "tester"));

        assertEquals("该映射已经是当前活动版本，如需重扫请创建对账任务", exception.getMessage());
    }

    /** 创建最小首次同步请求。 */
    private ForecastJobReq initialSyncRequest(long streamId, long mappingId) {
        ForecastJobReq request = new ForecastJobReq();
        request.setProfileId(3L);
        request.setStreamId(streamId);
        request.setMappingId(mappingId);
        request.setType(ForecastJobType.INITIAL_SYNC);
        request.setHistoryDays(180);
        return request;
    }

    /** 创建数据流快照。 */
    private ForecastStreamDO stream(long streamId, Long activeMappingId) {
        ForecastStreamDO stream = new ForecastStreamDO();
        stream.setId(streamId);
        stream.setProfileId(3L);
        stream.setActiveMappingId(activeMappingId);
        stream.setDeleted(false);
        return stream;
    }

    /** 创建占用首次同步槽的任务快照。 */
    private ForecastJobDO activeJob(long id, long streamId, long mappingId) {
        ForecastJobDO job = new ForecastJobDO();
        job.setId(id);
        job.setProfileId(3L);
        job.setStreamId(streamId);
        job.setMappingId(mappingId);
        job.setJobType(ForecastJobType.INITIAL_SYNC.name());
        job.setStatus(ForecastJobStatus.QUEUED.name());
        job.setActiveConcurrencyKey("INITIAL_SYNC:STREAM:" + streamId);
        return job;
    }
}
