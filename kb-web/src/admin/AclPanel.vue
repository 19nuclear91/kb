<script setup lang="ts">
import { useSession } from '../SessionProvider';
import type { Acl, ResourceKind } from './spaceApi';
import AclPanelContent from './AclPanelContent.vue';

withDefaults(defineProps<{ kind: ResourceKind; resourceId: string; archived?: boolean }>(), { archived: false });
const emit = defineEmits<{ changed: []; data: [items: Acl[] | undefined] }>();
const auth = useSession();
</script>

<template><AclPanelContent :key="`${auth.state.epoch}:${kind}:${resourceId}`" :kind="kind" :resource-id="resourceId" :archived="archived" @changed="emit('changed')" @data="emit('data', $event)" /></template>
