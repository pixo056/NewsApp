import type { CapacitorConfig } from '@capacitor/cli';

// Local development only. Android uses adb reverse; iOS Simulator runs on the backend's Mac.
// No remote server is contacted and no app-store deployment is configured.
const config: CapacitorConfig = {
  appId: 'ru.uunit.pulse.local',
  appName: 'Пульс',
  webDir: 'dist',
  server: { url: 'http://localhost:5173', cleartext: true },
  android: { allowMixedContent: false },
};
export default config;
