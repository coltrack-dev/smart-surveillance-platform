<script setup lang="ts">
import { computed, ref, watch } from "vue";
import type { AnalyticsProfileSettings } from "@/types/AnalyticsControl";
import { normalizeProfile } from "@/services/analyticsProfileSettings";

const props = defineProps<{ open: boolean; initialProfile: AnalyticsProfileSettings }>();
const emit = defineEmits<{
  close: [];
  run: [profile: AnalyticsProfileSettings, saveAsDefault: boolean];
}>();

const model = ref("");
const classes = ref<number[]>([]);
const confidence = ref(0.5);
const devicePreference = ref("auto");
const targetFps = ref(10);
const saveAsDefault = ref(false);

const commonClasses = [
  { id: 0, label: "Person" },
  { id: 1, label: "Bicycle" },
  { id: 2, label: "Car" },
  { id: 3, label: "Motorcycle" }
];
const valid = computed(() => model.value.trim() !== "" && classes.value.length > 0);

watch(() => props.open, open => {
  if (!open) return;
  const profile = normalizeProfile(props.initialProfile);
  model.value = profile.model;
  classes.value = [...profile.classes];
  confidence.value = profile.confidence;
  devicePreference.value = profile.devicePreference;
  targetFps.value = profile.targetFps;
  saveAsDefault.value = false;
}, { immediate: true });

function submit(): void {
  if (!valid.value) return;
  emit("run", normalizeProfile({
    model: model.value,
    classes: classes.value,
    confidence: confidence.value,
    devicePreference: devicePreference.value,
    targetFps: targetFps.value
  }), saveAsDefault.value);
}
</script>

<template>
  <Teleport to="body">
    <div v-if="open" class="backdrop" @click.self="emit('close')">
      <form class="dialog" role="dialog" aria-modal="true" @submit.prevent="submit">
        <header><h3>Analytics model</h3><button type="button" class="close" @click="emit('close')">×</button></header>
        <label>Model
          <input v-model.trim="model" list="analytics-models" placeholder="yolo11s.pt">
          <datalist id="analytics-models">
            <option value="yolo11n.pt">Fastest</option><option value="yolo11s.pt">Balanced</option>
            <option value="yolo11m.pt">More accurate</option><option value="yolo11l.pt">High accuracy</option>
          </datalist>
        </label>
        <p class="hint">The model file must be available to the inference worker.</p>
        <fieldset><legend>Detected classes</legend>
          <label v-for="item in commonClasses" :key="item.id" class="check">
            <input v-model="classes" type="checkbox" :value="item.id">{{ item.label }} ({{ item.id }})
          </label>
        </fieldset>
        <div class="grid">
          <label>Confidence<input v-model.number="confidence" type="number" min="0.05" max="1" step="0.05"></label>
          <label>Target FPS
            <input v-model.number="targetFps" type="number" min="1" max="60" step="1">
          </label>
          <label>Device<select v-model="devicePreference"><option value="auto">Auto</option><option value="cuda:0">GPU (cuda:0)</option><option value="cpu">CPU</option></select></label>
        </div>
        <label class="check default"><input v-model="saveAsDefault" type="checkbox">Use these settings by default</label>
        <p v-if="classes.length === 0" class="error">Select at least one object class.</p>
        <footer><button type="button" @click="emit('close')">Cancel</button><button type="submit" class="primary" :disabled="!valid">Run analysis</button></footer>
      </form>
    </div>
  </Teleport>
</template>

<style scoped>
.backdrop{position:fixed;inset:0;z-index:1200;display:grid;place-items:center;padding:20px;background:#0009}.dialog{width:min(520px,100%);padding:20px;border-radius:12px;background:#fff;color:#1e293b;box-shadow:0 20px 50px #0005}header,footer{display:flex;align-items:center;justify-content:space-between;gap:12px}h3{margin:0}label,fieldset{display:block;margin-top:14px;font-weight:600}input,select{box-sizing:border-box;width:100%;margin-top:5px;padding:9px;border:1px solid #cbd5e1;border-radius:6px;font:inherit}fieldset{padding:4px 12px 12px;border:1px solid #cbd5e1;border-radius:8px}.check{display:inline-flex;align-items:center;gap:6px;margin:8px 16px 0 0;font-weight:400}.check input{width:auto;margin:0}.grid{display:grid;grid-template-columns:repeat(3,1fr);gap:12px}.hint,.error{margin:5px 0 0;color:#64748b;font-size:12px}.error{color:#b42318}.default{display:flex}footer{justify-content:flex-end;margin-top:20px}button{padding:9px 14px;border:0;border-radius:6px;cursor:pointer}.close{padding:2px 8px;background:transparent;font-size:24px}.primary{background:#7c3aed;color:#fff}.primary:disabled{cursor:not-allowed;opacity:.5}@media(max-width:560px){.grid{grid-template-columns:1fr}}
</style>
