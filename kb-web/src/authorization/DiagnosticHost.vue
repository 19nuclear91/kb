<script setup lang="ts">
import { computed, defineAsyncComponent, ref, shallowRef, watch } from 'vue';
import { perm, useAdminAccess } from '../admin/access';
import type { ResourceKind } from '../admin/spaceApi';
import type { WriteIntent } from '../admin/api';
import type { User } from './api';
import { useSession } from '../SessionProvider';
import { useResourceNotice } from '../admin/spaceUi';

const props = defineProps<{ kind: ResourceKind; resourceId: string; spaceId: string; managed: boolean; blocked?: boolean; writeIntent?: WriteIntent | null; preview?: boolean; selectedUser?: User | null }>();
const emit = defineEmits<{ recheck: [] }>();
const auth = useSession(), access = useAdminAccess(), open = ref(false);
const chosen = shallowRef<User | null>(null);
// 仅固定失败提示跨资格重核保留；目标、候选和结果仍随 Panel 卸载清空。
const notice = useResourceNotice(() => `diagnostic:${props.kind}:${props.resourceId}:${props.spaceId}`);
const Panel = defineAsyncComponent(() => import('./DiagnosticPanel.vue'));
const managed = computed(() => auth.state.phase === 'ready' && props.managed && access.status === 'ready' && access.spaceStatus === 'ready' && !access.checking);
const eligible = computed(() => managed.value && access.has(perm.diagnose));
function clearSelection() { open.value = false; chosen.value = null; }
function close() { clearSelection(); notice.value = ''; }
function start() { notice.value = ''; open.value = true; }
function rejected(status: number) {
  notice.value = status === 403 ? '权限诊断请求被拒绝，请核对当前资格。' : '权限诊断暂不可用，请稍后重试。';
  clearSelection();
  // 父级先捕获正式表单，再进入会令私有子树卸载的资格核对。
  if (status !== 405) { emit('recheck'); access.check(true); }
}
watch([eligible, () => props.resourceId, () => props.spaceId, () => auth.state.epoch], clearSelection, { flush: 'sync' });
watch(() => props.selectedUser, user => { if (eligible.value && user) { chosen.value = user; open.value = true; } }, { flush: 'sync' });
</script>

<template>
  <section v-if="eligible || managed && notice && !blocked" aria-label="权限诊断区块">
    <p v-if="notice && !blocked" role="alert">{{ notice }}</p>
    <button v-if="eligible && !open" type="button" :disabled="blocked" @click="start">{{ preview ? '预演影响' : '权限诊断' }}</button>
    <template v-if="eligible && open"><button type="button" @click="close">关闭诊断</button>
      <Panel :key="`${auth.state.epoch}:${kind}:${resourceId}:${spaceId}`" :kind="kind" :resource-id="resourceId" :space-id="spaceId" :blocked="blocked" :write-intent="writeIntent" :preview="preview" :selected-user="chosen" @recheck="rejected" />
    </template>
  </section>
</template>
