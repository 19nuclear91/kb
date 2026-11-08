<script setup lang="ts">
import { useSession } from '../SessionProvider';
import { useResource } from '../useResource';
import Feedback from '../ui/Feedback.vue';
import { favoritePage } from './api';
import FavoriteList from './FavoriteList.vue';

const auth = useSession();
const query = useResource(signal => favoritePage(auth.session, null, signal), 'favorites');
</script>

<template>
  <Feedback :loading="query.loading" :error="query.error" :retry="query.reload" />
  <FavoriteList v-if="query.data" :first="query.data" :refresh="query.reload" />
</template>
