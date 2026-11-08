import { ApiError, filePath } from './api';
import type { Session } from './session';

export async function readFile(session: Session, path: string, signal: AbortSignal): Promise<Blob> {
  const response = await session.send(filePath(path), { signal });
  if (!response.ok) { await response.body?.cancel().catch(() => {}); throw new ApiError(response.status); }
  const type = (response.headers.get('Content-Type') ?? '').split(';')[0].trim().toLowerCase();
  if (!type || type === 'application/json' || type.endsWith('+json') || (path.endsWith('/preview') && !['application/pdf', 'text/plain', 'text/markdown'].includes(type))) {
    await response.body?.cancel().catch(() => {}); throw new ApiError(415);
  }
  const blob = await response.blob();
  if (signal.aborted) throw new DOMException('请求已停止', 'AbortError');
  return blob;
}
