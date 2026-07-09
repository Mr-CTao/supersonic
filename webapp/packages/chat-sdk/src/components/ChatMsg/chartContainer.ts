/**
 * ECharts 容器就绪检测模块。
 *
 * 负责在聊天消息图表挂载后等待 DOM 容器具备有效宽高，再触发 ECharts 初始化或重绘。
 * 这样可以兼容 antd 6 升级后弹层、折叠区域和聊天流式渲染导致的短暂零尺寸状态。
 */

/**
 * 判断图表容器是否已经具备可供 ECharts 初始化的有效尺寸。
 *
 * @param chartElement 图表 DOM 容器。
 * @returns 容器存在且宽高均大于 0 时返回 true。
 * @throws 不主动抛出异常；容器不存在时返回 false。
 */
export const hasVisibleChartSize = (chartElement?: HTMLElement | null) => {
  return Boolean(chartElement?.clientWidth && chartElement.clientHeight);
};

/**
 * 在图表容器尺寸可用后执行渲染。
 *
 * @param getChartElement 获取当前图表 DOM 容器的方法。
 * @param renderChart 真正执行 ECharts 初始化或 setOption 的方法。
 * @returns 清理函数，用于组件卸载或依赖变更时取消监听。
 * @throws 不主动抛出异常；非浏览器环境会在容器可用时直接尝试渲染。
 */
export const renderChartWhenContainerReady = (
  getChartElement: () => HTMLElement | null | undefined,
  renderChart: () => void
) => {
  const runIfReady = () => {
    const chartElement = getChartElement();
    if (!hasVisibleChartSize(chartElement)) {
      return false;
    }
    renderChart();
    return true;
  };

  if (runIfReady() || typeof window === 'undefined') {
    return () => {};
  }

  const chartElement = getChartElement();
  if (!chartElement || typeof ResizeObserver === 'undefined') {
    const timer = window.setTimeout(runIfReady, 100);
    return () => {
      window.clearTimeout(timer);
    };
  }

  const observer = new ResizeObserver(() => {
    if (runIfReady()) {
      observer.disconnect();
    }
  });

  // ECharts 在零尺寸容器 init 会报警；这里观察首个有效尺寸，等布局完成后再创建实例。
  observer.observe(chartElement);
  const frame = window.requestAnimationFrame(runIfReady);

  return () => {
    window.cancelAnimationFrame(frame);
    observer.disconnect();
  };
};
