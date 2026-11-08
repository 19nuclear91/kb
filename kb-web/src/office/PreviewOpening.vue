<script setup lang="ts">
import { ref, shallowRef, watch } from 'vue';
import { ElButton } from 'element-plus';
import { ApiError, isAbort } from '../api';
import FilePreview from '../FilePreview.vue';
import { useSession } from '../SessionProvider';
import KbAlert from '../ui/KbAlert.vue';
import KbDialog from '../ui/KbDialog.vue';
import KbSpin from '../ui/KbSpin.vue';
import { closeOffice, heartbeat, launchOffice, officeError, previewInfo, type OfficeLaunch } from './api';

const props = defineProps<{ path: string; fileName?: string }>();
const emit = defineEmits<{ close: []; denied: [] }>();
const auth = useSession();
const attempt = ref(0), mode = ref('loading'), error = ref('');
const launch = shallowRef<OfficeLaunch | null>(null), ready = ref(false);
const frame = shallowRef<HTMLIFrameElement | null>(null), legacyFailure = shallowRef<ApiError | null>(null);
let stopPreview: (error: unknown, notifyDenied?: boolean) => void = () => {};
watch([() => auth.session, () => props.path, () => props.fileName, attempt], ([session, path, fileName], _, onCleanup) => {
  const controller = new AbortController();
  let current: OfficeLaunch | null = null, timer: ReturnType<typeof setTimeout> | undefined;
  let requestTimer: ReturnType<typeof setTimeout> | undefined;
  mode.value = 'loading'; launch.value = null; ready.value = false; error.value = ''; legacyFailure.value = null;
  const fail = (failure: unknown, notifyDenied = true) => {
    if (controller.signal.aborted) {
      return;
    }
    launch.value = null; mode.value = 'error'; error.value = officeError(failure);
    controller.abort(); clearTimeout(timer); clearTimeout(requestTimer);
    if (current) {
      closeOffice(session, current.sessionId);
      current = null;
    }
    if (notifyDenied && failure instanceof ApiError && [401, 403, 404].includes(failure.status)) {
      emit('denied');
    }
  };
  stopPreview = fail;
  const openingRequest = async <T,>(request: () => Promise<T>): Promise<T> => {
    const deadline = setTimeout(() => fail(new ApiError(0)), 15000);
    requestTimer = deadline;
    try {
      return await request();
    } finally {
      clearTimeout(deadline);
    }
  };
  const beat = async () => {
    if (!current || controller.signal.aborted) {
      return;
    }
    try {
      await heartbeat(session, current.sessionId, AbortSignal.any([controller.signal, AbortSignal.timeout(5000)]));
      if (!controller.signal.aborted) {
        timer = setTimeout(() => void beat(), current.heartbeatSeconds * 1000);
      }
    } catch (failure) {
      fail(failure);
    }
  };
  void (async () => {
    let info;
    try {
      info = await openingRequest(() => previewInfo(session, path, controller.signal));
    } catch (failure) {
      if (controller.signal.aborted) {
        return;
      }
      // 缺少端点不等于文档被删除，旧协议只按受保护文件的 MIME 展示 PDF/文本。
      if (failure instanceof ApiError && [404, 405].includes(failure.status)) {
        if (/\.(docx|xlsx|pptx)$/i.test(fileName ?? '')) {
          fail(failure, false);
          return;
        }
        legacyFailure.value = failure;
        mode.value = 'legacy';
        return;
      }
      throw failure;
    }
    if (controller.signal.aborted) {
      return;
    }
    if (info.mode === 'DIRECT') {
      mode.value = 'direct';
      return;
    }
    if (info.mode !== 'OFFICE') {
      mode.value = 'error';
      error.value = info.mode === 'UNSUPPORTED' ? '该格式不支持在线预览。' : 'Office 预览尚未启用。';
      return;
    }
    const result = await openingRequest(() => launchOffice(session, path, controller.signal));
    if (controller.signal.aborted) {
      closeOffice(session, result.sessionId);
      return;
    }
    current = result; launch.value = result; mode.value = 'office';
    timer = setTimeout(() => void beat(), result.heartbeatSeconds * 1000);
  })().catch(failure => {
    if (!isAbort(failure)) {
      fail(failure);
    }
  });
  onCleanup(() => {
    controller.abort(); clearTimeout(timer); clearTimeout(requestTimer);
    if (current) {
      closeOffice(session, current.sessionId);
    }
  });
}, { immediate: true });
watch([launch, frame, () => auth.session], ([launched, iframe], _, onCleanup) => {
  if (!launched || !iframe) {
    return;
  }
  let active = true;
  const origin = new URL(launched.launchAction).origin;
  const stop = () => {
    if (active) {
      stopPreview(new ApiError(503));
    }
  };
  const timeout = setTimeout(stop, 30000);
  const message = (event: MessageEvent) => {
    if (!active || event.source !== iframe.contentWindow || event.origin !== origin || !event.data
      || event.data.protocolVersion !== 1 || event.data.sessionId !== launched.sessionId) {
      return;
    }
    if (event.data.type === 'kb-office-ready') {
      clearTimeout(timeout);
      ready.value = true;
    } else if (['kb-office-error', 'kb-office-closed'].includes(event.data.type)) {
      stop();
    }
  };
  window.addEventListener('message', message);
  const form = document.createElement('form'), input = document.createElement('input');
  form.method = 'POST'; form.action = launched.launchAction; form.target = iframe.name; form.style.display = 'none';
  input.type = 'hidden'; input.name = 'launchToken'; input.value = launched.launchToken; form.append(input); document.body.append(form);
  try {
    form.submit();
  } catch {
    stop();
  } finally {
    input.value = '';
    form.remove();
  }
  onCleanup(() => {
    active = false;
    clearTimeout(timeout);
    window.removeEventListener('message', message);
  });
}, { flush: 'post' });
const unsupported = () => stopPreview(legacyFailure.value, false);
</script>

<template>
  <FilePreview v-if="mode === 'direct'" :path="path" @close="emit('close')" @denied="emit('denied')" />
  <FilePreview v-else-if="mode === 'legacy'" :path="path" :on-unsupported="unsupported" @close="emit('close')" @denied="emit('denied')" />
  <KbDialog v-else title="Office 版本预览" open width="96vw" top="16px" @close="emit('close')">
    <div style="height: calc(100dvh - 130px); min-height: 280px; display: flex; flex-direction: column">
      <KbAlert v-if="error" type="warning" :title="error"><template #action><ElButton @click="attempt++">重试预览</ElButton></template></KbAlert>
      <template v-else>
        <p role="status">{{ ready ? '文档已就绪 · 只读预览' : '正在打开文档…' }}</p><KbSpin v-if="!ready" />
        <iframe v-if="launch" ref="frame" :name="`office-${launch.sessionId}`" title="Office 只读版本预览" src="about:blank" sandbox="allow-scripts allow-same-origin allow-forms" referrerpolicy="no-referrer" allow="clipboard-read 'none'; clipboard-write 'none'" :style="{ border: 0, flex: 1, width: '100%', minHeight: 0, visibility: ready ? 'visible' : 'hidden' }" />
      </template>
    </div>
  </KbDialog>
</template>
