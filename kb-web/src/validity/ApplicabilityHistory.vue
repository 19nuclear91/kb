<script setup lang="ts">
import { ElButton } from 'element-plus';
import { useSession } from '../SessionProvider';
import { useCursorList } from '../admin/hooks';
import Feedback from '../ui/Feedback.vue';
import { validityApi, type Change } from './api';
import { declarationLabel } from './labels';

const props = defineProps<{ documentId: string; versionId: string }>();
const auth = useSession();
const list = useCursorList<Change>((cursor, signal) => validityApi.history(auth.session, props, cursor, signal), () => `applicability-history:${props.documentId}/${props.versionId}`);
</script>
<template>
  <section aria-label="适用期修改历史"><h3>适用期修改历史</h3><Feedback :loading="list.loading" :error="list.error" :retry="list.reload" />
    <p v-if="!list.loading && !list.error && !list.items.length">暂无适用期修改记录</p>
    <article v-for="item in list.items" :key="item.id"><p>{{ item.changedAt }} · {{ item.visibleToReaders ? '已对读者可见时更正' : '候选声明修改' }}</p>
      <p>修改前：{{ declarationLabel({ effectiveFrom: item.beforeEffectiveFrom, untilKind: item.beforeUntilKind, effectiveUntil: item.beforeEffectiveUntil }) }}</p>
      <p>修改后：{{ declarationLabel({ effectiveFrom: item.afterEffectiveFrom, untilKind: item.afterUntilKind, effectiveUntil: item.afterEffectiveUntil }) }}</p>
      <p v-if="item.reason" style="white-space: pre-wrap">原因：{{ item.reason }}</p>
    </article><ElButton v-if="list.hasMore" :disabled="list.loading" @click="list.more">加载更多修改记录</ElButton>
  </section>
</template>
