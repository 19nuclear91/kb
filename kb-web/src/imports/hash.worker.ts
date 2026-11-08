/** 每个 Worker 同时仅处理一个有界文件；终止 Worker 即释放读取及摘要上下文。 */
self.onmessage = async (event: MessageEvent<{ file: File; maxBytes: number }>) => {
  try {
    const { file, maxBytes } = event.data;
    if (!Number.isSafeInteger(file.size) || file.size < 1 || file.size > maxBytes) throw new Error('invalid file');
    const bytes = await file.arrayBuffer();
    if (bytes.byteLength !== file.size) throw new Error('changed file');
    const digest = await crypto.subtle.digest('SHA-256', bytes);
    self.postMessage({ sha256: [...new Uint8Array(digest)].map(value => value.toString(16).padStart(2, '0')).join('') });
  } catch { self.postMessage({ error: 'HASH_FAILED' }); }
};
