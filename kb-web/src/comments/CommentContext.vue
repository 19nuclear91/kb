<script setup lang="ts">
import { ref, shallowReactive, shallowRef } from 'vue';
import { inFlight, type Binding, type WriteBindings } from '../admin/hooks';
import { composerScope, provideThread } from './thread';
import Denied from './Denied.vue';
import Roots from './Roots.vue';

const props = defineProps<{ documentId: string; versionId: string; versionLabel: string; writes: WriteBindings; onDenied?: () => void }>();
// 只追踪条目增删；Binding 保持原对象，供 sending WeakMap 识别在途绑定。
const writes = shallowReactive(props.writes);
const denied = ref(false), generation = ref(0), round = ref(0);
const own = new Set<string>(), erased = shallowRef<ReadonlySet<string>>(new Set());
function deny() {
  writes.clear();
  denied.value = true;
  props.onDenied?.();
}
function copies(origin: { parentId: string | null; body: string }) {
  const source = `|${composerScope(props.documentId, props.versionId, origin.parentId)}`;
  return [...writes].filter(([key, entry]) => key.endsWith(source) && entry.intent.body?.body === origin.body && !inFlight(entry)).map(([, entry]) => entry);
}
function erase(id: string, snapshot: readonly Binding[] = []) {
  own.delete(id);
  const suffix = `|${composerScope(props.documentId, props.versionId, id)}`;
  for (const [key, entry] of writes) {
    if (key.endsWith(suffix) || (snapshot.includes(entry) && !inFlight(entry))) {
      writes.delete(key);
    }
  }
  erased.value = new Set([...erased.value, id]);
}
provideThread({
  get documentId() { return props.documentId; },
  get versionId() { return props.versionId; },
  writes,
  get round() { return round.value; },
  deny, own, copies, erased: erase, recheck: () => { round.value++; },
});
function recover() {
  denied.value = false;
  generation.value++;
}
</script>

<template>
  <section class="panel" aria-label="版本评论"><h2>版本评论 · 固定版本 {{ versionLabel }}</h2>
    <p class="muted">评论只属于该版本，不会随新版本发布迁移；内容按纯文本显示，不执行链接或图片。</p>
    <Denied v-if="denied" :erased="erased" :on-recover="onDenied ? undefined : recover" />
    <Roots v-else :key="generation" :erased="erased" />
  </section>
</template>
