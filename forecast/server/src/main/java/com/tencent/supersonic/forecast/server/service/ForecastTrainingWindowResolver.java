package com.tencent.supersonic.forecast.server.service;

import com.tencent.supersonic.common.pojo.exception.InvalidArgumentException;
import com.tencent.supersonic.forecast.server.repository.ForecastDecisionRepository.ActualDateRange;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Forecast 看板训练区间解析与校验器。
 *
 * <p>
 * 职责：统一自动训练窗口和用户自定义窗口的日期语义，确保窗口落在当前方向的数据边界内、 自定义样本不少于最低天数，且训练截止严格早于预测起点以阻断数据泄漏。该类无共享可变状态，
 * 每次调用只处理请求局部值，因此并发请求之间不需要加锁。
 */
final class ForecastTrainingWindowResolver {

    static final int MINIMUM_CUSTOM_TRAINING_DAYS = 14;

    private ForecastTrainingWindowResolver() {}

    /**
     * 判断请求是否携带一组完整的自定义训练区间。
     *
     * @param requestedStart 用户选择的训练起始日。
     * @param requestedEnd 用户选择的训练截止日。
     * @return 两个日期均存在时返回 {@code true}，均为空时返回 {@code false}。
     * @throws InvalidArgumentException 仅提供一个边界时抛出。
     */
    static boolean hasCustomWindow(LocalDate requestedStart, LocalDate requestedEnd) {
        if ((requestedStart == null) != (requestedEnd == null)) {
            throw new InvalidArgumentException("请同时选择训练起始日和截止日");
        }
        return requestedStart != null;
    }

    /**
     * 解析本次预览真正参与模型计算的训练区间。
     *
     * <p>
     * 例如数据范围为 2025-01-01 至 2025-03-31、预测起点为 2025-03-25 时，自定义训练 截止日最多只能选择 2025-03-24；未传自定义区间时，仍按
     * Profile 的 historyDays 自动 截取并受数据最早日期约束。
     *
     * @param dataRange 当前方向的完整业务日边界。
     * @param forecastStart 预测或回测起始日。
     * @param configuredHistoryDays Profile 配置的自动训练天数。
     * @param requestedStart 用户选择的训练起始日。
     * @param requestedEnd 用户选择的训练截止日。
     * @return 已校验、可直接用于查询和模型计算的训练区间。
     * @throws InvalidArgumentException 日期越界、样本不足或存在数据泄漏风险时抛出。
     */
    static TrainingWindow resolve(ActualDateRange dataRange, LocalDate forecastStart,
            int configuredHistoryDays, LocalDate requestedStart, LocalDate requestedEnd) {
        boolean customWindow = hasCustomWindow(requestedStart, requestedEnd);
        LocalDate maximumTrainingEnd = forecastStart.minusDays(1);
        if (maximumTrainingEnd.isAfter(dataRange.latestDate())) {
            maximumTrainingEnd = dataRange.latestDate();
        }
        if (maximumTrainingEnd.isBefore(dataRange.firstDate())) {
            throw new InvalidArgumentException("预测起始日前没有可用训练数据");
        }
        if (!customWindow) {
            int historyDays = Math.max(1, configuredHistoryDays);
            LocalDate automaticStart = maximumTrainingEnd.minusDays(historyDays - 1L);
            if (automaticStart.isBefore(dataRange.firstDate())) {
                automaticStart = dataRange.firstDate();
            }
            return new TrainingWindow(automaticStart, maximumTrainingEnd, false);
        }

        // 自定义窗口必须先受当前方向的数据真实边界约束，不能依赖前端禁用日期保证安全。
        if (requestedStart.isBefore(dataRange.firstDate())
                || requestedEnd.isAfter(dataRange.latestDate())) {
            throw new InvalidArgumentException(String.format("训练区间必须位于当前统计口径的数据范围 %s 至 %s 内",
                    dataRange.firstDate(), dataRange.latestDate()));
        }
        if (requestedStart.isAfter(requestedEnd)) {
            throw new InvalidArgumentException("训练起始日不能晚于训练截止日");
        }
        if (!requestedEnd.isBefore(forecastStart)) {
            throw new InvalidArgumentException("训练截止日必须早于预测起始日，避免未来数据泄漏");
        }
        long inclusiveDays = ChronoUnit.DAYS.between(requestedStart, requestedEnd) + 1L;
        if (inclusiveDays < MINIMUM_CUSTOM_TRAINING_DAYS) {
            throw new InvalidArgumentException(
                    "自定义训练区间至少需要 " + MINIMUM_CUSTOM_TRAINING_DAYS + " 个自然日");
        }
        return new TrainingWindow(requestedStart, requestedEnd, true);
    }

    /**
     * 已解析的训练日期窗口。
     *
     * @param startInclusive 训练起始日（含）。
     * @param endInclusive 训练截止日（含）。
     * @param custom 是否来自用户自定义。
     */
    record TrainingWindow(LocalDate startInclusive, LocalDate endInclusive, boolean custom) {}
}
