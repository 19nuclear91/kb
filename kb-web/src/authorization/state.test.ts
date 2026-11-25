import { effectScope, ref } from 'vue';
import { expect, it, vi } from 'vitest';
import { deferred } from '../test/fixtures';
import { useDiagnosticRead } from './state';
import { ApiError } from '../api';

it('草稿、身份和可见性变化取消请求，忽略无法取消的迟到成功', async () => {
  const scope = effectScope(), binding = ref('epoch1:space1:user1:draft1'), enabled = ref(true), rejected = vi.fn();
  const read = scope.run(() => useDiagnosticRead<string>(() => binding.value, () => enabled.value, rejected))!;
  const first = deferred<string>(), second = deferred<string>();
  const a = read.run(() => first.promise), b = read.run(() => second.promise);
  second.resolve('最新'); await b; first.resolve('过期'); await a; expect(read.data.value).toBe('最新');
  for (const next of ['epoch1:space1:user1:draft2', 'epoch1:space1:user2:draft2', 'epoch1:space2:user2:draft2', 'epoch2:space2:user2:draft2']) {
    const late = deferred<string>(), request = read.run(() => late.promise); binding.value = next;
    late.resolve('迟到'); await request; expect(read.data.value).toBeUndefined();
  }
  const late = deferred<string>(), request = read.run(() => late.promise); enabled.value = false; late.resolve('隐藏期间'); await request;
  expect(read.data.value).toBeUndefined(); enabled.value = true;
  await read.run(async () => { throw new ApiError(403); }); expect(rejected).toHaveBeenCalledWith(403);
  scope.stop(); expect(read.data.value).toBeUndefined();
});
