<script setup lang="ts">
import { computed, ref } from 'vue';
import { ElButton } from 'element-plus';
import type { CommentItem } from './api';
import type { CommentList } from './hooks';
import CommentBody from './CommentBody.vue';
import Replies from './Replies.vue';

const props = defineProps<{ item: CommentItem; erased: ReadonlySet<string>; list: CommentList }>();
const open = ref(false);
const gone = computed(() => props.item.deleted || props.erased.has(props.item.commentId));
</script>

<template>
  <article class="comment" :aria-label="`根评论 ${item.commentId}`">
    <CommentBody :item="item" :erased="erased" @erased="list.tombstone(item.commentId)" />
    <ElButton size="small" @click="open = !open">{{ open ? '收起回复' : '查看回复' }}</ElButton>
    <Replies v-if="open" :key="`${item.commentId}:${gone}`" :root="item.commentId" :root-deleted="gone" :erased="erased" />
  </article>
</template>
