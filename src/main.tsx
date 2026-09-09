import React from "react";
import ReactDOM from "react-dom/client";
import { platform } from "@tauri-apps/plugin-os";
import App from "./App";
import WebLanding from "./WebLanding";
import { installCompatShims } from "./lib/compat";
import {
  applyTheme,
  getStoredTheme,
  syncThemeFromSettings,
} from "./lib/utils/theme";

installCompatShims();

const isTauriRuntime = "__TAURI_INTERNALS__" in window;

// Set platform before render so CSS can scope per-platform (e.g. scrollbar styles)
document.documentElement.dataset.platform = isTauriRuntime ? platform() : "web";

// Apply the last-known theme synchronously before render to avoid a flash of
// the wrong palette, then reconcile with the persisted setting once it loads.
applyTheme(getStoredTheme());
if (isTauriRuntime) {
  syncThemeFromSettings();
}

// Initialize i18n
import "./i18n";

// Initialize model store (loads models and sets up event listeners)
import { useModelStore } from "./stores/modelStore";
if (isTauriRuntime) {
  useModelStore.getState().initialize();
}

ReactDOM.createRoot(document.getElementById("root") as HTMLElement).render(
  <React.StrictMode>
    {isTauriRuntime ? <App /> : <WebLanding />}
  </React.StrictMode>,
);
