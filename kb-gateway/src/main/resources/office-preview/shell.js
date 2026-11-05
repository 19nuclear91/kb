/* 此脚本只在隔离预览 Origin 运行，不接收主应用登录凭据。 */
(() => {
  'use strict';
  const sessionId = document.body.dataset.session;
  const parentOrigin = document.body.dataset.parent;
  const status = document.getElementById('status');
  let editor;
  let closed = false;
  const controller = new AbortController();
  const timer = setTimeout(() => { controller.abort(); fail(); }, 25000);
  const notify = type => {
    if (!closed) parent.postMessage({ type, protocolVersion: 1, sessionId }, parentOrigin);
  };
  function fail() {
    if (closed) return;
    clearTimeout(timer);
    status.hidden = false;
    status.textContent = '文档未能打开，请关闭后重试。';
    notify('kb-office-error');
    editor?.destroyEditor();
    editor = undefined;
  }
  function close() {
    if (closed) return;
    notify('kb-office-closed');
    closed = true;
    clearTimeout(timer);
    controller.abort();
    editor?.destroyEditor();
    editor = undefined;
  }
  window.addEventListener('pagehide', close, { once: true });
  window.addEventListener('beforeunload', close, { once: true });
  fetch('./configuration', { credentials: 'same-origin', cache: 'no-store', signal: controller.signal })
    .then(response => {
      if (!response.ok || !response.headers.get('content-type')?.includes('application/json')) throw new Error('unavailable');
      return response.json();
    })
    .then(config => {
      if (closed) return;
      if (config?.editorConfig?.mode !== 'view' || typeof config.token !== 'string') throw new Error('invalid configuration');
      const script = document.createElement('script');
      script.src = `./web-apps/apps/api/documents/api.js`;
      script.onerror = fail;
      script.onload = () => {
        if (closed) return;
        try {
          editor = new DocsAPI.DocEditor('viewer', { ...config, events: {
            onDocumentReady: () => { clearTimeout(timer); status.hidden = true; notify('kb-office-ready'); },
            onError: fail,
            onRequestEditRights: fail
          }});
        } catch { fail(); }
      };
      document.head.appendChild(script);
    }).catch(() => { if (!closed) fail(); });
})();
