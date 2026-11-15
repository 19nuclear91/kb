<script setup lang="ts">
import { ElButton } from 'element-plus';
import KbDialog from '../ui/KbDialog.vue';
import WriteNotice from '../admin/WriteNotice.vue';
import { useAdminWrite, type WriteOutcome } from '../admin/hooks';
import { validityWrite, validityWrites, type MineItem, type Receipt } from '../validity/api';

const props = defineProps<{ item: MineItem; asOfDate: string }>();
const emit = defineEmits<{ confirmed: [value: Receipt]; rejected: []; verify: []; close: [] }>();
const write = useAdminWrite(() => `stewardship-confirm:${props.item.documentId}`, validityWrite);
function receive(result: WriteOutcome<Receipt>) { if (result.status === 'confirmed') emit('confirmed', result.value); else if (result.status === 'rejected' && [403, 404, 409].includes(result.error.status)) emit('rejected'); }
function confirm() { if (!write.pending) void write.submit<Receipt>(() => validityWrites.confirm(props.item.documentId, props.item.stewardshipVersion)).then(receive); }
const retry = () => { void write.retry<Receipt>().then(receive); };
const close = () => { if (!write.pending) emit('close'); };
</script>
<template><KbDialog title="确认资料仍然准确" :open="true" @close="close">
  <p>确认此资料内容仍然准确：{{ item.title }}（文档 {{ item.documentId }}）。确认后到期日按提交时的业务日期加 {{ item.cycleMonths }} 个月重算；本次读取业务日期 {{ asOfDate }}。</p>
  <p>只有当前合格责任人可确认。重新读取清单不等于获得写入回执。</p>
  <WriteNotice :write="write" :on-retry="retry" :on-verify="() => emit('verify')" :disabled="write.busy" />
  <template #footer><ElButton :disabled="write.pending" @click="close">取消确认</ElButton><ElButton type="primary" :disabled="write.pending" @click="confirm">确认此资料仍然准确</ElButton></template>
</KbDialog></template>
