<script setup lang="ts">
import {computed, onMounted, reactive, ref} from "vue";
import {useAnalyticsStore} from "@/stores/analytics";
import type {AnalyticsEvent, AnalyticsEventFilters} from "@/types/AnalyticsEvent";

const store = useAnalyticsStore();
const selectedEvent = ref<AnalyticsEvent | null>(null);
const filters = reactive({
  cameraId: "",
  eventType: "",
  objectType: "",
  from: "",
  to: ""
});

const pageLabel = computed(() => store.totalPages === 0
    ? "0 / 0"
    : `${store.currentPage + 1} / ${store.totalPages}`
);

function toIso(value: string): string | undefined {
  return value ? new Date(value).toISOString() : undefined;
}

function requestFilters(page = 0): AnalyticsEventFilters {
  return {
    cameraId: filters.cameraId.trim() || undefined,
    eventType: filters.eventType || undefined,
    objectType: filters.objectType.trim() || undefined,
    from: toIso(filters.from),
    to: toIso(filters.to),
    page,
    size: store.pageSize
  };
}

async function search(page = 0): Promise<void> {
  selectedEvent.value = null;
  await store.load(requestFilters(page));
}

function resetFilters(): void {
  filters.cameraId = "";
  filters.eventType = "";
  filters.objectType = "";
  filters.from = "";
  filters.to = "";
  void search();
}

function formatDate(value: string): string {
  return new Intl.DateTimeFormat("ru-RU", {
    dateStyle: "short",
    timeStyle: "medium"
  }).format(new Date(value));
}

function formatConfidence(value: number | null): string {
  return value == null ? "—" : `${(value * 100).toFixed(1)}%`;
}

onMounted(() => search());
</script>

<template>
  <section class="analytics-page">
    <div class="page-heading">
      <div>
        <h1>Аналитика</h1>
        <p>События, обнаруженные камерами</p>
      </div>
      <span class="event-total">Всего: {{ store.totalElements }}</span>
    </div>

    <form class="filters" @submit.prevent="search()">
      <label>
        Камера
        <input v-model="filters.cameraId" placeholder="ID камеры">
      </label>
      <label>
        Тип события
        <select v-model="filters.eventType">
          <option value="">Все</option>
          <option value="OBJECT_DETECTED">Объект обнаружен</option>
          <option value="LINE_CROSSED">Пересечение линии</option>
        </select>
      </label>
      <label>
        Тип объекта
        <input v-model="filters.objectType" placeholder="PERSON, CAR…">
      </label>
      <label>
        От
        <input v-model="filters.from" type="date">
      </label>
      <label>
        До
        <input v-model="filters.to" type="date">
      </label>
      <div class="filter-actions">
        <button type="submit" :disabled="store.loading">Найти</button>
        <button type="button" class="secondary" @click="resetFilters">Сбросить</button>
      </div>
    </form>

    <div v-if="store.error" class="message error-message">
      {{ store.error }}
      <button class="link-button" @click="search(store.currentPage)">Повторить</button>
    </div>
    <div v-else-if="store.loading" class="message">Загрузка событий…</div>
    <div v-else-if="store.events.length === 0" class="message">События не найдены</div>

    <div v-else class="table-card">
      <table>
        <thead>
        <tr>
          <th>Время</th>
          <th>Камера</th>
          <th>Событие</th>
          <th>Объект</th>
          <th>Точность</th>
          <th>Track ID</th>
        </tr>
        </thead>
        <tbody>
        <tr
            v-for="event in store.events"
            :key="event.eventId"
            tabindex="0"
            @click="selectedEvent = event"
            @keydown.enter="selectedEvent = event"
        >
          <td>{{ formatDate(event.occurredAt) }}</td>
          <td>{{ event.cameraId }}</td>
          <td><span class="event-badge">{{ event.eventType }}</span></td>
          <td>{{ event.objectType || "—" }}</td>
          <td>{{ formatConfidence(event.confidence) }}</td>
          <td>{{ event.trackId ?? "—" }}</td>
        </tr>
        </tbody>
      </table>
    </div>

    <div v-if="!store.loading && store.totalPages > 0" class="pagination">
      <button
          class="secondary"
          :disabled="store.currentPage === 0"
          @click="search(store.currentPage - 1)"
      >Назад
      </button>
      <span>Страница {{ pageLabel }}</span>
      <button
          class="secondary"
          :disabled="store.currentPage + 1 >= store.totalPages"
          @click="search(store.currentPage + 1)"
      >Вперёд
      </button>
    </div>

    <div v-if="selectedEvent" class="drawer-backdrop" @click.self="selectedEvent = null">
      <aside class="event-drawer">
        <div class="drawer-header">
          <h2>Событие</h2>
          <button class="close-button" aria-label="Закрыть" @click="selectedEvent = null">×</button>
        </div>
        <dl>
          <dt>ID</dt>
          <dd>{{ selectedEvent.eventId }}</dd>
          <dt>Время</dt>
          <dd>{{ formatDate(selectedEvent.occurredAt) }}</dd>
          <dt>Камера</dt>
          <dd>{{ selectedEvent.cameraId }}</dd>
          <dt>Тип</dt>
          <dd>{{ selectedEvent.eventType }}</dd>
          <dt>Объект</dt>
          <dd>{{ selectedEvent.objectType || "—" }}</dd>
          <dt>Точность</dt>
          <dd>{{ formatConfidence(selectedEvent.confidence) }}</dd>
          <dt>Track ID</dt>
          <dd>{{ selectedEvent.trackId ?? "—" }}</dd>
          <dt>Кадр</dt>
          <dd>{{ selectedEvent.frameNumber ?? "—" }}</dd>
          <dt>Время видео</dt>
          <dd>{{ selectedEvent.videoTimeSeconds ?? "—" }} с</dd>
          <dt>Recording ID</dt>
          <dd>{{ selectedEvent.recordingId || "—" }}</dd>
        </dl>
        <details v-if="Object.keys(selectedEvent.attributes).length">
          <summary>Дополнительные атрибуты</summary>
          <pre>{{ JSON.stringify(selectedEvent.attributes, null, 2) }}</pre>
        </details>
      </aside>
    </div>
  </section>
</template>

<style scoped>
.analytics-page {
  padding: 24px;
}

.page-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 20px;
}

.page-heading h1 {
  margin: 0 0 4px;
}

.page-heading p {
  margin: 0;
  color: #64748b;
}

.event-total {
  color: #475569;
  font-weight: 600;
}

.filters {
  display: grid;
  grid-template-columns: repeat(5, minmax(140px, 1fr));
  gap: 14px;
  padding: 18px;
  margin-bottom: 20px;
  background: white;
  border-radius: 12px;
  box-shadow: 0 2px 8px rgb(15 23 42 / 7%);
}

.filters label {
  display: flex;
  flex-direction: column;
  gap: 6px;
  color: #475569;
  font-size: 13px;
  font-weight: 600;
}

input, select {
  width: 100%;
  min-height: 40px;
  padding: 8px 10px;
  border: 1px solid #cbd5e1;
  border-radius: 7px;
  background: white;
  color: #1f2937;
}

.filter-actions {
  display: flex;
  align-items: end;
  grid-column: 1 / -1;
}

button:disabled {
  cursor: not-allowed;
  opacity: .5;
}

.secondary {
  background: #e2e8f0;
  color: #334155;
}

.secondary:hover:not(:disabled) {
  background: #cbd5e1;
}

.message {
  padding: 50px 20px;
  text-align: center;
  color: #64748b;
  background: white;
  border-radius: 12px;
}

.error-message {
  color: #b91c1c;
}

.link-button {
  padding: 0;
  margin-left: 8px;
  background: transparent;
  color: #2563eb;
}

.table-card {
  overflow-x: auto;
  background: white;
  border-radius: 12px;
  box-shadow: 0 2px 8px rgb(15 23 42 / 7%);
}

table {
  width: 100%;
  border-collapse: collapse;
}

th, td {
  padding: 14px 16px;
  border-bottom: 1px solid #e2e8f0;
  text-align: left;
  white-space: nowrap;
}

th {
  color: #64748b;
  font-size: 12px;
  letter-spacing: .04em;
  text-transform: uppercase;
}

tbody tr {
  cursor: pointer;
}

tbody tr:hover, tbody tr:focus {
  background: #f8fafc;
  outline: none;
}

.event-badge {
  display: inline-block;
  padding: 4px 8px;
  color: #1d4ed8;
  background: #dbeafe;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 700;
}

.pagination {
  display: flex;
  justify-content: center;
  align-items: center;
  gap: 14px;
  margin-top: 20px;
}

.drawer-backdrop {
  position: fixed;
  inset: 0;
  z-index: 20;
  display: flex;
  justify-content: flex-end;
  background: rgb(15 23 42 / 45%);
}

.event-drawer {
  width: min(480px, 100%);
  height: 100%;
  overflow-y: auto;
  padding: 24px;
  background: white;
  box-shadow: -8px 0 24px rgb(15 23 42 / 15%);
}

.drawer-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.drawer-header h2 {
  margin: 0;
}

.close-button {
  padding: 0 8px;
  margin: 0;
  background: transparent;
  color: #475569;
  font-size: 30px;
}

dl {
  display: grid;
  grid-template-columns: 125px minmax(0, 1fr);
  gap: 12px;
  margin-top: 28px;
}

dt {
  color: #64748b;
}

dd {
  margin: 0;
  overflow-wrap: anywhere;
}

details {
  margin-top: 24px;
}

pre {
  overflow-x: auto;
  padding: 12px;
  background: #f1f5f9;
  border-radius: 8px;
}

@media (max-width: 900px) {
  .filters {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (max-width: 560px) {
  .analytics-page {
    padding: 14px;
  }

  .filters {
    grid-template-columns: 1fr;
  }

  .page-heading {
    align-items: flex-start;
    gap: 12px;
  }
}
</style>

