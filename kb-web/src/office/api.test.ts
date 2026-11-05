import { expect, it, vi } from 'vitest';
import { ApiError } from '../api';
import type { Session } from '../session';
import { launchOffice, officeError, previewInfo, previewOrigin, versionTarget } from './api';

const path = '/api/documents/9007199254740993/versions/9223372036854775806/preview';
const signal = new AbortController().signal;
const info = { protocolVersion: 1, documentId: '9007199254740993', versionId: '9223372036854775806', mode: 'OFFICE', fileType: 'docx', previewPath: null };
const origin = 'https://preview.example.test';
const launch = () => ({ protocolVersion: 1, sessionId: 'a'.repeat(32), launchAction: `${origin}/s/${'a'.repeat(32)}/bootstrap`,
  launchToken: 'b'.repeat(43), expiresAt: Date.now() + 1800000, launchExpiresAt: Date.now() + 60000, heartbeatSeconds: 15 });
it('保持大整数目标，拒绝当前版本路径与缺失版本', async () => {
  const json = vi.fn().mockResolvedValue(info), session = { json } as unknown as Session;
  await expect(previewInfo(session, path, signal)).resolves.toEqual(info);
  expect(json.mock.calls[0][0]).toBe(path.replace('/preview', '/preview-info'));
  expect(() => versionTarget('/api/documents/1/preview')).toThrow();
  json.mockResolvedValue({ ...info, versionId: '1' });
  await expect(previewInfo(session, path, signal)).rejects.toMatchObject({ status: 503 });
  json.mockResolvedValue({ ...info, protocolVersion: 2 });
  await expect(previewInfo(session, path, signal)).rejects.toMatchObject({ status: 503 });
});
it('交接只接受精确隔离 Origin、SID 路径和有限期限', async () => {
  const json = vi.fn().mockResolvedValue(launch()), session = { json } as unknown as Session;
  await expect(launchOffice(session, path, signal, origin)).resolves.toMatchObject({ sessionId: 'a'.repeat(32) });
  for (const invalid of [{ launchAction: 'https://evil.test/s/a/bootstrap' }, { launchAction: `${origin}/s/${'a'.repeat(32)}/bootstrap?token=secret` }, { protocolVersion: 8 }, { expiresAt: Infinity }]) {
    json.mockResolvedValue({ ...launch(), ...invalid });
    await expect(launchOffice(session, path, signal, origin)).rejects.toMatchObject({ status: 503 });
  }
  expect(() => previewOrigin(location.origin)).toThrow();
  expect(() => previewOrigin('https://preview.example.test/path')).toThrow();
  expect(() => previewOrigin('http://preview.example.test')).toThrow();
});
it.each([0, 401, 403, 404, 405, 415, 429, 503])('错误 %i 不自动重建会话，显示 Office 专属提示', async status => {
  const json = vi.fn().mockRejectedValue(new ApiError(status));
  await expect(launchOffice({ json } as unknown as Session, path, signal, origin)).rejects.toMatchObject({ status });
  expect(json).toHaveBeenCalledOnce();
  expect(officeError(new ApiError(status))).not.toContain('部门额度');
});
