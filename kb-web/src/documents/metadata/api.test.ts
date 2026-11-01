import { describe, expect, it } from 'vitest';
import { decodeJson, encodeJson } from '../../api';
import { cleanText, decodeMetadata, metadataFailure, metadataWrites, queryBody } from './api';

describe('资料 API', () => {
  it('精确保留 null、大整数和停用词条', () => {
    const value = decodeMetadata(decodeJson('{"documentId":9223372036854775807,"spaceId":2,"title":"标题","summary":null,"category":null,"tags":[{"id":9223372036854775806,"name":"词","enabled":false}],"metadataVersion":9223372036854775807,"updatedAt":"2026-11-01T00:00:00Z"}'));
    expect(value.tags[0].enabled).toBe(false);
    const body = metadataWrites.update(value.documentId, { title: value.title, summary: null, categoryId: null, tagIds: value.tags.map(tag => tag.id), expectedMetadataVersion: value.metadataVersion }).body;
    expect(encodeJson(body)).toContain('"categoryId":null'); expect(encodeJson(body)).toContain('9223372036854775806');
  });
  it('按码点限制并拒绝重复身份和冲突条件', () => {
    expect(cleanText('😀'.repeat(512), 512)).toHaveLength(1024);
    expect(() => cleanText('😀'.repeat(513), 512)).toThrow(); expect(() => cleanText('\ud800', 512)).toThrow();
    expect(() => queryBody({ tagIds: ['1', '1'] }, 1)).toThrow(); expect(() => queryBody({ categoryId: '1', uncategorized: true }, 1)).toThrow();
    expect(queryBody({ titleQuery: '%_\\', tagIds: ['9', '2'] }, 1).tagIds).toEqual([2n, 9n]);
  });
  it('只消费已知错误分类', async () => {
    const response = (reasonCode: string) => new Response(JSON.stringify({ code: 500, data: null, reasonCode, message: 'private' }), { status: 409, headers: { 'Content-Type': 'application/json' } });
    expect((await metadataFailure(response('NAME_CONFLICT'))).message).toBe('名称已被占用。');
    expect((await metadataFailure(response('UNKNOWN'))).message).not.toContain('private');
  });
});
