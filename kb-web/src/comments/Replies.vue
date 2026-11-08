<script setup lang="ts">
import { useSession } from '../SessionProvider';
import { replyPage } from './api';
import { useCommentList } from './hooks';
import { holdReason, useDenial, useOwn, useRecheck, useThread } from './thread';
import ListStatus from './ListStatus.vue';
import Composer from './Composer.vue';
import CommentBody from './CommentBody.vue';
import More from './More.vue';

const props = defineProps<{ root: string; rootDeleted: boolean; erased: ReadonlySet<string> }>();
const auth = useSession(), thread = useThread();
const list = useCommentList((cursor, signal) => replyPage(auth.session, thread.documentId, thread.versionId, props.root, cursor, signal), () => `${thread.documentId}/${thread.versionId}/${props.root}/replies`);
useDenial(list, thread.deny);
useOwn(() => list.items, thread.own);
const rechecking = useRecheck(list, () => thread.round);
</script>

<template>
  <section class="comment-replies" aria-label="回复列表">
    <ListStatus :list="list" empty="暂无回复。" retry-label="重新读取回复" />
    <article v-for="item in list.items" :key="item.commentId" class="comment" :aria-label="`回复 ${item.commentId}`"><CommentBody :item="item" :erased="erased" @erased="list.tombstone(item.commentId)" /></article>
    <More :list="list" label="更多回复" :end="list.items.length > 0 ? '已显示全部可见回复。' : undefined" retry-label="重新读取回复" />
    <Composer v-if="list.loaded && !rootDeleted && list.canCreate" :parent="root" label="回复内容" :hold="holdReason(list, rechecking)" @settled="list.reload" />
    <p v-if="list.loaded && rootDeleted" class="muted">根评论已删除，已有回复保留，不能追加新回复。</p>
  </section>
</template>
