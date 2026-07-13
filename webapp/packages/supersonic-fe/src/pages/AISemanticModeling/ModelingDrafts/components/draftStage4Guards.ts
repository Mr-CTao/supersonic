/**
 * 阶段 4 前端只读门禁纯函数。
 *
 * 职责：集中判断管理级验证报告是否允许加载，避免 viewer/public 用户先发出敏感报告请求、
 * 再依赖服务端 404 拒绝。服务端权限校验仍是不可绕过的最终边界。
 */
import type { ModelingDraftStatus } from '@/services/semanticModelingDraft';

export type ValidationReportLoadContext = {
  currentVersionNo?: number;
  draftId?: number;
  hasManagePermission: boolean;
  open: boolean;
  status?: ModelingDraftStatus;
};

/**
 * 判断当前视图是否允许请求管理级验证报告。
 *
 * @param context Drawer、草稿、不可变版本、状态和服务端返回的管理权限上下文。
 * @returns 仅管理员查看 DRAFT/PENDING_APPROVAL 的明确版本时返回 true。
 * @throws 不抛出异常；缺失或未知上下文统一按不可加载处理。
 */
export function canLoadAdminValidationReport(context: ValidationReportLoadContext): boolean {
  return Boolean(
    context.open &&
      context.hasManagePermission &&
      context.draftId &&
      context.currentVersionNo &&
      context.status &&
      ['DRAFT', 'PENDING_APPROVAL'].includes(context.status),
  );
}

/**
 * 在管理报告门禁通过后调用加载器。
 *
 * @param context 管理报告加载上下文。
 * @param loader 真正访问统一 service 层的延迟加载器。
 * @returns 无权限时返回 undefined；允许时返回加载器结果。
 * @throws 原样传播 loader 异常，由调用 Hook 统一脱敏展示。
 */
export async function loadAdminValidationReportIfAllowed<T>(
  context: ValidationReportLoadContext,
  loader: () => Promise<T>,
): Promise<T | undefined> {
  if (!canLoadAdminValidationReport(context)) return undefined;
  return loader();
}
