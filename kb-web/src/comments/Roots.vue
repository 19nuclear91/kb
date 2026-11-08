<script setup lang="ts">
import { useSession } from '../SessionProvider';
import { rootPage } from './api';
import { useCommentList } from './hooks';
import { holdReason, useDenial, useOwn, useRecheck, useThread } from './thread';
import ListStatus from './ListStatus.vue';
import Composer from './Composer.vue';
import RootItem from './RootItem.vue';
import More from './More.vue';

defineProps<{ erased: ReadonlySet<string> }>();
const auth = useSession(), thread = useThread();
const list = useCommentList((cursor, signal) => rootPage(auth.session, thread.documentId, thread.versionId, cursor, signal), () => `${thread.documentId}/${thread.versionId}/roots`);
useDenial(list, thread.deny);
useOwn(() => list.items, thread.own);
const rechecking = useRecheck(list, () => thread.round);
</script>

<template>
  <ListStatus :list="list" empty="该版本暂无评论。" retry-label="重新读取评论" />
  <template v-if="list.loaded">
    <Composer v-if="list.canCreate" :parent="null" label="评论内容" :hold="holdReason(list, rechecking)" @settled="list.reload" />
    <p v-else class="muted">当前可以阅读该版本的评论，但没有发表评论的资格。</p>
  </template>
  <RootItem v-for="item in list.items" :key="item.commentId" :item="item" :erased="erased" :list="list" />
  <More :list="list" label="更多评论" :end="list.items.length > 0 ? '已显示全部可见根评论。' : undefined" retry-label="重新读取评论" />
</template>
