<script setup lang="ts">
import { useSession } from '../SessionProvider';
import { useResource } from '../useResource';
import { reviewPaths, type ReviewStatus } from '../reviews/api';
import Feedback from '../ui/Feedback.vue';
import KbAlert from '../ui/KbAlert.vue';

const props = defineProps<{ documentId: string; versionId: string }>();
const auth = useSession();
const query = useResource(signal => auth.session.json<ReviewStatus>(reviewPaths.status(props.documentId, props.versionId), { signal }), () => `${auth.state.epoch}:${props.documentId}/${props.versionId}`);
</script>

<template>
  <section class="panel"><h2>上传已受理</h2>
    <KbAlert v-if="query.error" type="info" title="提交成功，当前无法查看后续处理状态。" />
    <Feedback v-else :loading="query.loading" />
    <p v-if="query.data">版本 {{ versionId }} · {{ query.data.state.indexStatus }} · {{ query.data.state.reviewState }}。上传受理不表示已发布。</p>
    <a href="/reviews">前往本人版本核对</a>
  </section>
</template>
