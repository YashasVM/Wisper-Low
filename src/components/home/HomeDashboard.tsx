import { useCallback, useEffect, useMemo, useState } from "react";
import type { JSX } from "react";
import { listen } from "@tauri-apps/api/event";
import {
  ArrowUpRight,
  AudioWaveform,
  Check,
  ChevronRight,
  Download,
  History,
  Mic,
  Settings2,
  ShieldCheck,
  Sparkles,
  Zap,
} from "lucide-react";
import { useTranslation } from "react-i18next";
import { toast } from "sonner";
import { commands, type HistoryEntry, type ModelInfo } from "@/bindings";
import { formatRelativeTime } from "@/utils/dateFormat";
import { getTranslatedModelName } from "@/lib/utils/modelTranslation";
import { useModelStore } from "@/stores/modelStore";
import { Button } from "@/components/ui/Button";

interface HomeDashboardProps {
  onOpenSettings: () => void;
  onOpenHistory: () => void;
}

const EMPTY_HISTORY: HistoryEntry[] = [];

export default function HomeDashboard({
  onOpenSettings,
  onOpenHistory,
}: HomeDashboardProps): JSX.Element {
  const { t, i18n } = useTranslation();
  const {
    models,
    currentModel,
    downloadProgress,
    downloadingModels,
    selectModel,
    downloadModel,
  } = useModelStore();
  const [isRecording, setIsRecording] = useState(false);
  const [isFinishing, setIsFinishing] = useState(false);
  const [history, setHistory] = useState<HistoryEntry[]>(EMPTY_HISTORY);
  const [showAllModels, setShowAllModels] = useState(false);

  const refreshHistory = useCallback(async () => {
    try {
      const result = await commands.getHistoryEntries(null, 5);
      if (result.status === "ok") setHistory(result.data.entries);
    } catch {
      // History is supplementary; a transient database read should not stop
      // the primary record button from working.
    }
  }, []);

  useEffect(() => {
    let alive = true;
    const pollRecording = async () => {
      try {
        const recording = await commands.isRecording();
        if (alive) setIsRecording(recording);
      } catch {
        // The settings window can render briefly before the Tauri state exists.
      }
    };
    void pollRecording();
    const timer = window.setInterval(pollRecording, 350);
    void refreshHistory();
    const historyListener = listen("history-update-payload", () => {
      setIsFinishing(false);
      void refreshHistory();
    });
    const errorListener = listen("recording-error", () =>
      setIsFinishing(false),
    );

    return () => {
      alive = false;
      window.clearInterval(timer);
      historyListener.then((unlisten) => unlisten());
      errorListener.then((unlisten) => unlisten());
    };
  }, [refreshHistory]);

  const activeModel = models.find((model) => model.id === currentModel);
  const visibleModels = useMemo(() => {
    const sorted = [...models].sort(
      (a, b) => Number(b.is_recommended) - Number(a.is_recommended),
    );
    return showAllModels ? sorted : sorted.slice(0, 4);
  }, [models, showAllModels]);

  const toggleRecording = async () => {
    if (isRecording) {
      setIsFinishing(true);
      window.setTimeout(() => setIsFinishing(false), 15000);
    }
    const result = await commands.toggleTranscription();
    if (result.status !== "ok") {
      setIsFinishing(false);
      toast.error(t("dashboard.errors.toggle"));
    }
  };

  const chooseModel = async (model: ModelInfo) => {
    if (model.is_downloaded) {
      const selected = await selectModel(model.id);
      if (!selected) toast.error(t("dashboard.errors.model"));
      return;
    }

    const downloaded = await downloadModel(model.id);
    if (!downloaded) toast.error(t("dashboard.errors.download"));
  };

  const isBusy = (model: ModelInfo) => Boolean(downloadingModels[model.id]);

  return (
    <main className="dashboard-shell">
      <section className="dashboard-hero">
        <div className="dashboard-hero-copy">
          <div className="dashboard-eyebrow">
            <span className="dashboard-live-dot" />
            {t("dashboard.eyebrow")}
          </div>
          <h1>{t("dashboard.title")}</h1>
          <p>{t("dashboard.subtitle")}</p>
          <div className="dashboard-hero-actions">
            <button
              type="button"
              className={`record-button ${isRecording ? "record-button-active" : ""}`}
              onClick={toggleRecording}
              aria-pressed={isRecording}
            >
              <span className="record-button-icon">
                {isRecording ? <AudioWaveform size={26} /> : <Mic size={26} />}
              </span>
              <span>
                <strong>
                  {isRecording
                    ? t("dashboard.stopSpeaking")
                    : isFinishing
                      ? t("dashboard.transcribing")
                      : t("dashboard.startSpeaking")}
                </strong>
                <small>
                  {isRecording
                    ? t("dashboard.listeningHint")
                    : t("dashboard.startHint")}
                </small>
              </span>
            </button>
            <div className="dashboard-shortcut">
              <span>{t("dashboard.shortcutLabel")}</span>
              <kbd>{t("dashboard.shortcutCommand")}</kbd>
              <kbd>{t("dashboard.shortcutShift")}</kbd>
              <kbd>{t("dashboard.shortcutSpace")}</kbd>
            </div>
          </div>
        </div>
        <div
          className={`dashboard-orb ${isRecording ? "dashboard-orb-live" : ""}`}
        >
          <div className="dashboard-orb-ring dashboard-orb-ring-one" />
          <div className="dashboard-orb-ring dashboard-orb-ring-two" />
          <div className="dashboard-orb-core">
            <AudioWaveform size={48} strokeWidth={1.5} />
          </div>
        </div>
      </section>

      <section
        className="dashboard-stat-grid"
        aria-label={t("dashboard.statsLabel")}
      >
        <div className="dashboard-stat-card">
          <span className="dashboard-stat-icon">
            <ShieldCheck size={18} />
          </span>
          <div>
            <strong>{t("dashboard.stats.localValue")}</strong>
            <span>{t("dashboard.stats.localLabel")}</span>
          </div>
        </div>
        <div className="dashboard-stat-card">
          <span className="dashboard-stat-icon">
            <Zap size={18} />
          </span>
          <div>
            <strong>
              {activeModel
                ? getTranslatedModelName(activeModel, t)
                : t("dashboard.stats.noModel")}
            </strong>
            <span>{t("dashboard.stats.modelLabel")}</span>
          </div>
        </div>
        <div className="dashboard-stat-card">
          <span className="dashboard-stat-icon">
            <Sparkles size={18} />
          </span>
          <div>
            <strong>{t("dashboard.stats.instantValue")}</strong>
            <span>{t("dashboard.stats.instantLabel")}</span>
          </div>
        </div>
      </section>

      <section className="dashboard-content-grid">
        <div className="dashboard-panel dashboard-history-panel">
          <div className="dashboard-panel-heading">
            <div>
              <span className="dashboard-section-kicker">
                <History size={14} /> {t("dashboard.recentKicker")}
              </span>
              <h2>{t("dashboard.recentTitle")}</h2>
            </div>
            <button
              type="button"
              className="dashboard-text-button"
              onClick={onOpenHistory}
            >
              {t("dashboard.viewHistory")} <ArrowUpRight size={15} />
            </button>
          </div>
          {history.length === 0 ? (
            <div className="dashboard-empty-state">
              <span className="dashboard-empty-icon">
                <Mic size={20} />
              </span>
              <p>{t("dashboard.emptyHistory")}</p>
              <small>{t("dashboard.emptyHistoryHint")}</small>
            </div>
          ) : (
            <div className="dashboard-history-list">
              {history.map((entry) => (
                <div className="dashboard-history-item" key={entry.id}>
                  <div>
                    <p>
                      {entry.post_processed_text || entry.transcription_text}
                    </p>
                    <span>
                      {formatRelativeTime(
                        String(entry.timestamp),
                        i18n.language,
                      )}
                    </span>
                  </div>
                  <Check size={17} />
                </div>
              ))}
            </div>
          )}
        </div>

        <div className="dashboard-panel dashboard-quick-panel">
          <div className="dashboard-panel-heading">
            <div>
              <span className="dashboard-section-kicker">
                <Settings2 size={14} /> {t("dashboard.quickKicker")}
              </span>
              <h2>{t("dashboard.quickTitle")}</h2>
            </div>
          </div>
          <button
            type="button"
            className="dashboard-quick-row"
            onClick={onOpenSettings}
          >
            <span className="dashboard-quick-symbol">
              <Mic size={17} />
            </span>
            <span>
              <strong>{t("dashboard.quickMicrophone")}</strong>
              <small>{t("dashboard.quickMicrophoneHint")}</small>
            </span>
            <ChevronRight size={18} />
          </button>
          <button
            type="button"
            className="dashboard-quick-row"
            onClick={onOpenSettings}
          >
            <span className="dashboard-quick-symbol">
              <Settings2 size={17} />
            </span>
            <span>
              <strong>{t("dashboard.quickSettings")}</strong>
              <small>{t("dashboard.quickSettingsHint")}</small>
            </span>
            <ChevronRight size={18} />
          </button>
        </div>
      </section>

      <section className="dashboard-models-section">
        <div className="dashboard-section-heading">
          <div>
            <span className="dashboard-section-kicker">
              <Sparkles size={14} /> {t("dashboard.modelsKicker")}
            </span>
            <h2>{t("dashboard.modelsTitle")}</h2>
            <p>{t("dashboard.modelsSubtitle")}</p>
          </div>
          <Button
            variant="ghost"
            size="sm"
            onClick={() => setShowAllModels((value) => !value)}
          >
            {showAllModels ? t("dashboard.showLess") : t("dashboard.showAll")}{" "}
            <ChevronRight size={15} />
          </Button>
        </div>
        {visibleModels.length === 0 ? (
          <div className="dashboard-model-empty">{t("dashboard.noModels")}</div>
        ) : (
          <div className="dashboard-model-grid">
            {visibleModels.map((model) => {
              const active = model.id === currentModel;
              const busy = isBusy(model);
              const progress = downloadProgress[model.id]?.percentage ?? 0;
              return (
                <button
                  type="button"
                  className={`dashboard-model-card ${active ? "dashboard-model-active" : ""}`}
                  key={model.id}
                  onClick={() => void chooseModel(model)}
                  disabled={busy || isFinishing}
                >
                  <span className="dashboard-model-topline">
                    <span className="dashboard-model-badge">
                      {model.is_recommended
                        ? t("dashboard.recommended")
                        : model.supports_streaming
                          ? t("dashboard.liveBadge")
                          : t("dashboard.localBadge")}
                    </span>
                    {active && <Check size={16} />}
                  </span>
                  <strong>{getTranslatedModelName(model, t)}</strong>
                  <p>{model.description}</p>
                  <span className="dashboard-model-meta">
                    {model.size_mb} MB <span>·</span>{" "}
                    {model.supported_languages.length === 1
                      ? t("dashboard.english")
                      : t("dashboard.languages", {
                          count: model.supported_languages.length,
                        })}
                  </span>
                  {busy ? (
                    <span className="dashboard-model-action">
                      <Download size={14} /> {Math.round(progress)}%
                    </span>
                  ) : (
                    <span className="dashboard-model-action">
                      {active
                        ? t("dashboard.active")
                        : model.is_downloaded
                          ? t("dashboard.select")
                          : t("dashboard.download")}{" "}
                      <ArrowUpRight size={14} />
                    </span>
                  )}
                </button>
              );
            })}
          </div>
        )}
      </section>
    </main>
  );
}
