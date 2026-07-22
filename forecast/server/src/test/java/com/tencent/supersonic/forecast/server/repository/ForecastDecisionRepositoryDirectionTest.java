package com.tencent.supersonic.forecast.server.repository;

import com.tencent.supersonic.forecast.api.enums.ForecastDirection;
import com.tencent.supersonic.forecast.server.config.ForecastProperties;
import com.tencent.supersonic.forecast.server.repository.ForecastDecisionRepository.ActualDateRange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ForecastDecisionRepository} 看板方向过滤 SQL 与参数绑定测试。
 *
 * <p>
 * 该测试不依赖本机 PostgreSQL，只验证方向条件在数据库聚合前生效，且枚举值始终通过 PreparedStatement 参数传入而不是拼接到 SQL 中。
 */
@ExtendWith(MockitoExtension.class)
class ForecastDecisionRepositoryDirectionTest {

    @Mock
    private Connection connection;

    @Mock
    private PreparedStatement statement;

    @Mock
    private ResultSet resultSet;

    private ForecastDecisionRepository repository;

    /** 为每个测试创建无共享连接状态的仓储实例。 */
    @BeforeEach
    void setUp() throws Exception {
        ForecastProperties properties = new ForecastProperties();
        properties.getDecision().setSchema("forecast");
        repository = new ForecastDecisionRepository(properties);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(resultSet);
    }

    /** 指定入库时应追加参数化方向条件并顺延日期参数下标。 */
    @Test
    void shouldFilterDailyActualsBeforeAggregation() throws Exception {
        when(resultSet.next()).thenReturn(false);

        repository.findDailyActuals(connection, 10L, ForecastDirection.INBOUND,
                LocalDate.of(2025, 1, 1), LocalDate.of(2025, 2, 1));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sqlCaptor.capture());
        assertTrue(sqlCaptor.getValue().contains("profile_id=? AND direction=?"));
        verify(statement).setString(2, "INBOUND");
        verify(statement).setDate(3, Date.valueOf("2025-01-01"));
        verify(statement).setDate(4, Date.valueOf("2025-02-01"));
    }

    /** 全部方向沿用原查询口径，不生成多余方向条件。 */
    @Test
    void shouldKeepAllDirectionsWhenDirectionIsNull() throws Exception {
        when(resultSet.next()).thenReturn(false);

        repository.findDailyActuals(connection, 10L, LocalDate.of(2025, 1, 1),
                LocalDate.of(2025, 2, 1));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sqlCaptor.capture());
        assertFalse(sqlCaptor.getValue().contains("direction=?"));
        verify(statement).setDate(2, Date.valueOf("2025-01-01"));
        verify(statement).setDate(3, Date.valueOf("2025-02-01"));
    }

    /** 日期边界必须与所选出库方向使用同一过滤条件。 */
    @Test
    void shouldResolveDateRangeForSelectedDirection() throws Exception {
        when(resultSet.next()).thenReturn(true);
        when(resultSet.getDate(1)).thenReturn(Date.valueOf("2025-01-03"));
        when(resultSet.getDate(2)).thenReturn(Date.valueOf("2025-03-28"));

        ActualDateRange result = repository.findActualDateRange(connection, 10L,
                ForecastDirection.OUTBOUND, LocalDate.of(2025, 4, 1));

        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sqlCaptor.capture());
        assertTrue(sqlCaptor.getValue().contains("profile_id=? AND direction=?"));
        verify(statement).setString(2, "OUTBOUND");
        verify(statement).setDate(3, Date.valueOf("2025-04-01"));
        assertEquals(LocalDate.of(2025, 1, 3), result.firstDate());
        assertEquals(LocalDate.of(2025, 3, 28), result.latestDate());
    }
}
