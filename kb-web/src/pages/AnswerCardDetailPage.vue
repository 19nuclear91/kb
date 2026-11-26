<script setup lang="ts">
import { useRoute, RouterLink } from 'vue-router';
import { ElButton } from 'element-plus';
import { useSession } from '../SessionProvider';
import PageHeader from '../ui/PageHeader.vue';
import Feedback from '../ui/Feedback.vue';
import CardRevision from '../answerCards/CardRevision.vue';
import { cardApi } from '../answerCards/api';
import { stewardLabel } from '../answerCards/labels';
import { useCardResource } from '../answerCards/useCardResource';
const route = useRoute(), auth = useSession();
const id = () => String(route.params.cardId ?? ''), revision = () => String(route.params.revisionId ?? '');
const resource = useCardResource(() => `${id()}:${revision()}`, signal => cardApi.detail(auth.session, id(), revision(), signal));
function denied() { resource.clear(); resource.error.value = '所选标准答案当前不可访问，请主动重新查询。'; }
</script>
<template>
  <PageHeader title="标准答案固定修订" description="当前访问重新核对全部依据；旧结果不会自动替换成另一答案" />
  <RouterLink to="/answer-cards">重新浏览标准答案</RouterLink><ElButton @click="resource.reload">重新核对所选修订</ElButton>
  <Feedback :loading="resource.loading.value" :error="resource.error.value" />
  <template v-if="resource.data.value">
    <p>{{ stewardLabel[resource.data.value.stewardship.state] }}；核对日期 {{ resource.data.value.asOfDate }}</p>
    <CardRevision :value="resource.data.value.revision" @denied="denied" />
  </template>
</template>
