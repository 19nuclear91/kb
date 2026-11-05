import { ApiError, positiveId } from '../api';
import type { Session } from '../session';

export interface PreviewInfo {
  protocolVersion: 1; documentId: string; versionId: string;
  mode: 'DIRECT' | 'OFFICE' | 'UNSUPPORTED' | 'UNAVAILABLE'; reason: string | null; fileType: string; previewPath: string | null;
}
export interface OfficeLaunch {
  protocolVersion: 1; sessionId: string; launchAction: string; launchToken: string;
  expiresAt: number; launchExpiresAt: number; heartbeatSeconds: number;
}
export const configuredOrigin = () => String(import.meta.env.VITE_OFFICE_PREVIEW_ORIGIN ?? '');
export function versionTarget(path: string) {
  const match = /^\/api\/documents\/([1-9]\d*)\/versions\/([1-9]\d*)\/preview$/.exec(path);
  if (!match) throw new ApiError(400);
  return { documentId: positiveId(match[1]), versionId: positiveId(match[2]), base: path.slice(0, -8) };
}
export async function previewInfo(session: Session, path: string, signal: AbortSignal): Promise<PreviewInfo> {
  const target = versionTarget(path);
  const info = await session.json<PreviewInfo>(`${target.base}/preview-info`, { signal });
  if (!info || info.protocolVersion !== 1 || info.documentId !== target.documentId || info.versionId !== target.versionId
    || !['DIRECT', 'OFFICE', 'UNSUPPORTED', 'UNAVAILABLE'].includes(info.mode) || typeof info.fileType !== 'string'
    || (info.mode === 'DIRECT' && (info.previewPath !== path || !['pdf', 'txt', 'md'].includes(info.fileType)))
    || (info.mode === 'OFFICE' && !['docx', 'xlsx', 'pptx'].includes(info.fileType))) throw new ApiError(503);
  return info;
}
export function previewOrigin(value = configuredOrigin()): string {
  try {
    const url = new URL(value);
    if (url.origin !== value || url.protocol !== 'https:' || url.hostname === location.hostname || url.username || url.password) throw new Error();
    return value;
  } catch { throw new ApiError(503); }
}
export async function launchOffice(session: Session, path: string, signal: AbortSignal, origin = previewOrigin()): Promise<OfficeLaunch> {
  const target = versionTarget(path);
  const launch = await session.json<OfficeLaunch>(`${target.base}/office-preview/sessions`, { method: 'POST', signal });
  if (!launch || launch.protocolVersion !== 1 || !/^[a-f0-9]{32}$/.test(launch.sessionId)
    || launch.launchAction !== `${origin}/s/${launch.sessionId}/bootstrap` || !/^[A-Za-z0-9_-]{43}$/.test(launch.launchToken)
    || !Number.isSafeInteger(launch.expiresAt) || launch.expiresAt <= Date.now() || launch.expiresAt > Date.now() + 3600000
    || !Number.isSafeInteger(launch.launchExpiresAt) || launch.launchExpiresAt <= Date.now() || launch.launchExpiresAt > launch.expiresAt
    || !Number.isInteger(launch.heartbeatSeconds) || launch.heartbeatSeconds < 5 || launch.heartbeatSeconds > 60) throw new ApiError(503);
  return launch;
}
export async function heartbeat(session: Session, id: string, signal: AbortSignal) {
  if (!/^[a-f0-9]{32}$/.test(id)) throw new ApiError(400);
  const lease = await session.json<{ expiresAt: number; absoluteExpiresAt: number }>(`/api/office-preview/sessions/${id}/heartbeat`, { method: 'POST', signal });
  if (!lease || !Number.isSafeInteger(lease.expiresAt) || lease.expiresAt <= Date.now()
    || !Number.isSafeInteger(lease.absoluteExpiresAt) || lease.expiresAt > lease.absoluteExpiresAt) throw new ApiError(503);
}
export function closeOffice(session: Session, id: string) {
  if (!/^[a-f0-9]{32}$/.test(id)) return;
  const controller = new AbortController(), timer = setTimeout(() => controller.abort(), 3000);
  void session.json(`/api/office-preview/sessions/${id}`, { method: 'DELETE', signal: controller.signal })
    .catch(() => { /* 关闭结果未知时由有限租约回收；不重试创建或复活查看器。 */ }).finally(() => clearTimeout(timer));
}
export function officeError(error: unknown): string {
  if (error instanceof ApiError) return ({ 0: '打开结果尚未确认，旧请求将自动过期。请稍后主动重试。',
    401: '登录已失效，请重新登录。', 403: '当前身份不能继续预览，请重新核对。',
    404: '服务不支持此预览，或该版本当前不可访问。', 405: '当前服务尚未支持 Office 预览。',
    415: '该文件不能在线预览，请在有下载权限时下载原件。', 429: '预览会话已达上限，请关闭其他预览后重试。',
    503: 'Office 预览暂不可用，请稍后主动重试。' } as Record<number, string>)[error.status] ?? '预览请求未能完成。';
  return '预览请求未能完成，请稍后主动重试。';
}
