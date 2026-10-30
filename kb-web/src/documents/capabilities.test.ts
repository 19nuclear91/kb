import { expect, it } from 'vitest';
import { decodeJson } from '../api';
import { canDelete } from './capabilities';

it('只有布尔 true 授予删除资格，旧响应和读取失败均禁用', () => {
  for (const value of [undefined, null, {}, { canUploadVersion: true, canRetry: true },
    ...[false, 'true', 'false', 1, 0, null, [], {}].map(canDelete => ({ canDelete }))]) {
    expect(canDelete(value)).toBe(false);
  }
  expect(canDelete({ canDelete: true })).toBe(true);
});

it('从实际 JSON 消费删除字段时保留原能力字段及大整数 ID', () => {
  const value = decodeJson<{ documentId: string; canDownload: boolean; canUploadVersion: boolean; canRetry: boolean }>(
    '{"documentId":9223372036854775807,"canDownload":false,"canUploadVersion":true,"canRetry":true,"canDelete":true}');
  expect(canDelete(value)).toBe(true);
  expect(value).toMatchObject({ documentId: '9223372036854775807', canDownload: false, canUploadVersion: true, canRetry: true });
});
