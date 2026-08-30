import type { AnalyticsProfileSettings } from "@/types/AnalyticsControl";

const STORAGE_KEY = "surveillance.analytics.default-profile.v1";

export const FALLBACK_ANALYTICS_PROFILE: AnalyticsProfileSettings = {
  model: "yolo11n.pt",
  classes: [0],
  confidence: 0.5,
  devicePreference: "auto",
  targetFps: 10
};

export function loadDefaultAnalyticsProfile(): AnalyticsProfileSettings {
  try {
    const stored = window.localStorage.getItem(STORAGE_KEY);
    if (!stored) return { ...FALLBACK_ANALYTICS_PROFILE, classes: [0] };
    return normalizeProfile(JSON.parse(stored) as Partial<AnalyticsProfileSettings>);
  } catch {
    return { ...FALLBACK_ANALYTICS_PROFILE, classes: [0] };
  }
}

export function saveDefaultAnalyticsProfile(profile: AnalyticsProfileSettings): void {
  window.localStorage.setItem(STORAGE_KEY, JSON.stringify(normalizeProfile(profile)));
}

export function normalizeProfile(
    value: Partial<AnalyticsProfileSettings>
): AnalyticsProfileSettings {
  const classes = Array.isArray(value.classes)
      ? [...new Set(value.classes.filter(item => Number.isInteger(item) && item >= 0))]
      : [0];
  return {
    model: value.model?.trim() || FALLBACK_ANALYTICS_PROFILE.model,
    classes: classes.length ? classes : [0],
    confidence: clamp(Number(value.confidence), 0.05, 1, 0.5),
    devicePreference: value.devicePreference?.trim() || "auto",
    targetFps: clamp(Number(value.targetFps), 1, 60, 10)
  };
}

function clamp(value: number, min: number, max: number, fallback: number): number {
  return Number.isFinite(value) ? Math.min(max, Math.max(min, value)) : fallback;
}
