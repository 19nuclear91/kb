<script setup lang="ts">
import { ElButton, ElDescriptions, ElDescriptionsItem } from 'element-plus';
import Feedback from '../../ui/Feedback.vue';
import PageHeader from '../../ui/PageHeader.vue';
import { useSession } from '../../SessionProvider';
import { useAdminResource } from '../hooks';
import Note from '../Note.vue';
import { lifecycleStatus } from './api';
import Events from './Events.vue';
import Emergency from './Emergency.vue';

const auth = useSession();
const status = useAdminResource(signal => lifecycleStatus(auth.session, signal), 'lifecycle-status');
</script>

<template>
  <PageHeader title="员工生命周期" description="钉钉离职处置、人工核对与独立应急保障"><template #extra><ElButton :disabled="status.loading" @click="status.reload">刷新接入状态</ElButton></template></PageHeader>
  <Feedback :loading="status.loading" :error="status.error" :retry="status.reload" />
  <section v-if="status.data" class="panel"><ElDescriptions :column="2"><ElDescriptionsItem label="订阅">{{ status.data.subscriptionEnabled ? '已启用' : '已关闭；已有访问限制仍生效' }}</ElDescriptionsItem>
    <ElDescriptionsItem label="Stream 连接">{{ status.data.connected ? '已连接' : '未连接' }}</ElDescriptionsItem><ElDescriptionsItem label="应急保障">{{ status.data.emergencyReady ? '当前就绪' : '未就绪' }}</ElDescriptionsItem>
    <ElDescriptionsItem label="全局受管账号">{{ String(status.data.counts.managed) }}</ElDescriptionsItem><ElDescriptionsItem label="全局受限账号">{{ String(status.data.counts.restricted) }}</ElDescriptionsItem>
    <ElDescriptionsItem label="全局未匹配项">{{ String(status.data.counts.unmapped) }}</ElDescriptionsItem><ElDescriptionsItem label="全局待核对项">{{ String(status.data.counts.review) }}</ElDescriptionsItem><ElDescriptionsItem label="全局失败项">{{ String(status.data.counts.failed) }}</ElDescriptionsItem>
  </ElDescriptions><Note>全局摘要与下方游标列表独立读取。未匹配、待核对及失败项均不代表成功停权。</Note></section>
  <Events @changed="status.reload" /><Emergency @changed="status.reload" />
</template>
