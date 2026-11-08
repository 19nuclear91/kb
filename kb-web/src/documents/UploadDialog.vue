<script setup lang="ts">
import { computed, reactive, ref, shallowRef, useId, watch } from 'vue';
import { ElButton, ElForm, ElFormItem, ElInput, ElOption, ElSelect, type FormInstance, type FormRules } from 'element-plus';
import { useSession } from '../SessionProvider';
import { useDocumentOperations } from './OperationContext';
import { operationFailure } from './operations';
import { ApiError, isAbort, messageOf, positiveId } from '../api';
import type { QueryPage, UploadResult, UploadSpace } from '../types';
import KbAlert from '../ui/KbAlert.vue';
import KbDialog from '../ui/KbDialog.vue';

const props = defineProps<{ open: boolean; documentId?: string; access?: { key: string; canUploadVersion: boolean; checking: boolean }; onRecheck?: () => void }>();
const emit = defineEmits<{ close: []; uploaded: [result: UploadResult]; denied: [] }>();
const auth = useSession(), documents = useDocumentOperations();
const spaces = shallowRef<UploadSpace[]>([]), cursor = ref<string | null>(null), levels = ref<number[]>([]);
const busy = ref(false), loading = ref(false), error = ref('');
const file = shallowRef<File | null>(null), form = ref<FormInstance>();
const fileId = useId();
const values = reactive({ spaceId: undefined as string | undefined, securityLevel: undefined as number | undefined, title: '' });
const rules: FormRules = { spaceId: [{ required: true, message: '请选择可上传空间' }], securityLevel: [{ required: true, message: '请选择密级' }] };
let lock = false, active: AbortController | null = null, rejectedKey: string | null = null;
const deniedAt = ref<string | null>(null);
const paused = computed(() => !!props.documentId && (!props.access?.canUploadVersion || props.access.checking || deniedAt.value === props.access.key));
const disabled = computed(() => documents.state.exclusive || loading.value || (props.documentId ? paused.value : !spaces.value.length || !levels.value.length));
watch([() => props.open, () => props.documentId, () => auth.session], ([open, documentId, session], _, onCleanup) => {
  if (!open) {
    return;
  }
  const controller = new AbortController();
  active = controller;
  error.value = '';
  spaces.value = [];
  levels.value = [];
  cursor.value = null;
  file.value = null;
  values.spaceId = undefined;
  values.securityLevel = undefined;
  values.title = '';
  form.value?.clearValidate();
  rejectedKey = null;
  deniedAt.value = null;
  if (!documentId) {
    loading.value = true;
    void Promise.all([session.json<QueryPage<UploadSpace>>('/api/auth/upload-spaces', { signal: controller.signal }),
      session.json<number[]>('/api/auth/security-levels', { signal: controller.signal })]).then(([page, dictionary]) => {
      if (!controller.signal.aborted) {
        spaces.value = page.items;
        cursor.value = page.nextCursor;
        levels.value = dictionary;
        loading.value = false;
      }
    }).catch(failure => {
      if (!controller.signal.aborted && !isAbort(failure)) {
        error.value = messageOf(failure);
        loading.value = false;
      }
    });
  }
  onCleanup(() => {
    controller.abort();
    active = null;
  });
}, { immediate: true });
function close() {
  if (!busy.value) {
    emit('close');
  }
}
function chooseFile(event: Event) {
  file.value = (event.target as HTMLInputElement).files?.[0] ?? null;
}
async function more() {
  loading.value = true;
  error.value = '';
  const request = active;
  try {
    const page = await auth.session.json<QueryPage<UploadSpace>>(`/api/auth/upload-spaces?cursor=${encodeURIComponent(cursor.value!)}`, { signal: request?.signal });
    if (request && !request.signal.aborted && active === request) {
      spaces.value = [...new Map([...spaces.value, ...page.items].map(item => [item.spaceId, item])).values()];
      cursor.value = page.nextCursor;
    }
  } catch (failure) {
    if (active === request && !request?.signal.aborted && !isAbort(failure)) {
      error.value = messageOf(failure);
    }
  } finally {
    if (active === request && !request?.signal.aborted) {
      loading.value = false;
    }
  }
}
async function submit() {
  if (!props.open || lock || disabled.value || (props.documentId && rejectedKey !== null && rejectedKey === props.access?.key)) {
    return;
  }
  lock = true;
  const request = active;
  try {
    if (!await form.value?.validate().catch(() => false)) {
      return;
    }
    if (!props.open || disabled.value || !request || request.signal.aborted || active !== request) {
      return;
    }
    if (!file.value || file.value.size === 0 || file.value.size > 50 * 1024 * 1024) {
      error.value = '请选择非空且不超过 50 MiB 的文件；实际限制由服务器判定。';
      return;
    }
    if (!/\.(pdf|txt|md|doc|docx|xls|xlsx|ppt|pptx)$/i.test(file.value.name)) {
      error.value = '只支持 PDF、TXT、Markdown（.md）和常规 Office 文档。';
      return;
    }
    const operations = documents.operations, operation = operations?.beginWrite('upload-version');
    if (operations && !operation) {
      return;
    }
    busy.value = true;
    error.value = '';
    try {
      const body = new FormData();
      body.set('file', file.value);
      if (!props.documentId) {
        body.set('spaceId', positiveId(values.spaceId));
        body.set('securityLevel', String(values.securityLevel));
        if (values.title) {
          body.set('title', values.title);
        }
      }
      const result = await auth.session.json<UploadResult>(props.documentId ? `/api/documents/${positiveId(props.documentId)}/versions` : '/api/documents', { method: 'POST', body, signal: request.signal });
      operation?.settle('confirmed');
      if (request.signal.aborted) {
        return;
      }
      emit('uploaded', result);
      emit('close');
    } catch (failure) {
      operation?.settle(operationFailure(failure));
      if (!request.signal.aborted && active === request && !isAbort(failure)) {
        error.value = failure instanceof ApiError && failure.status === 0 ? '提交结果尚未确认，请先核对文档列表，不会自动重复上传。' : messageOf(failure);
        if (props.documentId && failure instanceof ApiError && [403, 404].includes(failure.status)) {
          rejectedKey = props.access?.key ?? '';
          deniedAt.value = rejectedKey;
          emit('denied');
        }
      }
    }
  } finally {
    lock = false;
    busy.value = false;
  }
}
</script>

<template>
  <KbDialog :title="documentId ? '上传新版本' : '上传文档'" :open="open" @close="close">
    <KbAlert v-if="error" type="warning" :title="error" style="margin-bottom: 16px" />
    <KbAlert v-if="paused" type="warning" title="上传资格尚未确认或已失效，提交已暂停。" style="margin-bottom: 16px">
      <template v-if="onRecheck" #action><ElButton :disabled="access?.checking || busy || documents.state.exclusive" @click="onRecheck">重新核对上传资格</ElButton></template>
    </KbAlert>
    <ElForm ref="form" :model="values" :rules="rules" label-position="top" @submit.prevent="submit">
      <template v-if="!documentId">
        <ElFormItem prop="spaceId" label="目标空间"><ElSelect v-model="values.spaceId" aria-label="目标空间" :loading="loading" placeholder="选择有编辑资格的空间">
          <ElOption v-for="space in spaces" :key="space.spaceId" :value="space.spaceId" :label="space.name" />
        </ElSelect></ElFormItem>
        <ElButton v-if="cursor" size="small" :disabled="loading" @click="more">更多可上传空间</ElButton>
        <ElFormItem prop="title" label="文档标题（可选）"><ElInput v-model="values.title" aria-label="文档标题（可选）" placeholder="缺省使用文件名" /></ElFormItem>
        <ElFormItem prop="securityLevel" label="密级"><ElSelect v-model="values.securityLevel" aria-label="密级"><ElOption v-for="value in levels" :key="value" :value="value" :label="`密级 ${value}`" /></ElSelect></ElFormItem>
      </template>
      <ElFormItem label="原始文件" :for="fileId" required><input :id="fileId" aria-label="原始文件" type="file" accept=".pdf,.txt,.md,.doc,.docx,.xls,.xlsx,.ppt,.pptx" @change="chooseFile" /></ElFormItem>
      <p class="muted">支持 PDF、文本和常规 Office。上传成功后将异步解析。</p>
      <ElButton type="primary" native-type="submit" :loading="busy" :disabled="disabled">提交文件</ElButton>
    </ElForm>
  </KbDialog>
</template>
