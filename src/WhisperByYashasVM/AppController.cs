using System.Diagnostics;
using System.Windows;
using System.Windows.Forms;
using WhisperByYashasVM.Models;
using WhisperByYashasVM.Services;
using WhisperByYashasVM.UI;
using Application = System.Windows.Application;
using MessageBox = System.Windows.MessageBox;

namespace WhisperByYashasVM;

public sealed class AppController : IDisposable
{
    private readonly NotifyIcon _trayIcon;
    private readonly OverlayWindow _overlayWindow;
    private readonly GlobalShortcutService _shortcutService;
    private readonly AudioCaptureService _audioCapture;
    private readonly WhisperService _whisperService;
    private readonly OutputService _outputService;
    private readonly ConfigService _configService;
    private readonly SystemSpecService _systemSpecService;
    private readonly StartupRegistrationService _startupRegistrationService;
    private readonly SemaphoreSlim _transcriptionGate = new(1, 1);
    private SettingsWindow? _settingsWindow;
    private RecordingSessionService? _recordingSession;
    private AppConfig _config;

    public AppController()
    {
        _configService = new ConfigService();
        _systemSpecService = new SystemSpecService();
        _startupRegistrationService = new StartupRegistrationService();
        _config = _configService.Load();

        _overlayWindow = new OverlayWindow();
        _shortcutService = new GlobalShortcutService();
        _audioCapture = new AudioCaptureService();
        _whisperService = new WhisperService();
        _outputService = new OutputService();

        _trayIcon = new NotifyIcon
        {
            Text = "Whisper -By YashasVM",
            Visible = true,
            Icon = System.Drawing.SystemIcons.Application,
            ContextMenuStrip = BuildMenu()
        };
    }

    public void Start()
    {
        if (!RunFirstSetupIfNeeded())
        {
            return;
        }

        ApplyStartupPreference();
        RecreateRecordingSession();

        _shortcutService.HotkeyTriggered += OnHotkeyTriggered;
        _shortcutService.Start();

        _trayIcon.BalloonTipTitle = "Whisper -By YashasVM";
        _trayIcon.BalloonTipText = "App is running in tray. Press Win+Y to start recording.";
        _trayIcon.ShowBalloonTip(2500);
    }

    private bool RunFirstSetupIfNeeded()
    {
        if (_config.FirstRunCompleted)
        {
            return true;
        }

        var report = _systemSpecService.Evaluate(_config.ModelDirectory);
        var setupWindow = new SetupWindow(_config, report, _whisperService);
        var result = setupWindow.ShowDialog();
        if (result != true || setupWindow.ResultConfig is null)
        {
            Application.Current.Shutdown();
            return false;
        }

        _config = setupWindow.ResultConfig;
        _configService.Save(_config);
        return true;
    }

    private void ApplyStartupPreference()
    {
        var exePath = Process.GetCurrentProcess().MainModule?.FileName ?? string.Empty;
        if (string.IsNullOrWhiteSpace(exePath))
        {
            return;
        }

        _startupRegistrationService.Apply(_config.StartWithWindows, exePath);
    }

    private void RecreateRecordingSession()
    {
        if (_recordingSession is not null)
        {
            _recordingSession.AudioLevelChanged -= OnAudioLevelChanged;
            _recordingSession.SpeechStateChanged -= OnSpeechStateChanged;
            _recordingSession.RecordingCompleted -= OnRecordingCompleted;
            _recordingSession.Dispose();
        }

        _recordingSession = new RecordingSessionService(_audioCapture, _config.SilenceTimeoutMs, _config.MaxRecordingMs);
        _recordingSession.AudioLevelChanged += OnAudioLevelChanged;
        _recordingSession.SpeechStateChanged += OnSpeechStateChanged;
        _recordingSession.RecordingCompleted += OnRecordingCompleted;
    }

    private ContextMenuStrip BuildMenu()
    {
        var menu = new ContextMenuStrip();
        menu.Items.Add("Settings", null, (_, _) =>
        {
            if (_settingsWindow is null)
            {
                _settingsWindow = new SettingsWindow(_config, _configService, _startupRegistrationService, OnConfigUpdated);
            }

            _settingsWindow.ApplyConfig(_config);
            if (!_settingsWindow.IsVisible)
            {
                _settingsWindow.Show();
            }

            _settingsWindow.Activate();
        });
        menu.Items.Add("Exit", null, (_, _) => Application.Current.Shutdown());
        return menu;
    }

    private void OnConfigUpdated(AppConfig config)
    {
        _config = config;
        RecreateRecordingSession();
    }

    private void OnAudioLevelChanged(float rms)
    {
        Application.Current.Dispatcher.Invoke(() =>
        {
            _overlayWindow.UpdateWaveform(rms);
        });
    }

    private void OnSpeechStateChanged(bool speaking)
    {
        Application.Current.Dispatcher.Invoke(() =>
        {
            _overlayWindow.SetSpeechState(speaking);
        });
    }

    private void OnHotkeyTriggered(object? sender, EventArgs e)
    {
        if (_recordingSession is null)
        {
            return;
        }

        if (_recordingSession.State != RecordingState.Idle)
        {
            return;
        }

        if (_recordingSession.Start())
        {
            Application.Current.Dispatcher.Invoke(() =>
            {
                _overlayWindow.ShowListening();
            });
        }
    }

    private async void OnRecordingCompleted(object? sender, byte[] wavData)
    {
        if (_recordingSession is null)
        {
            return;
        }

        if (wavData.Length == 0)
        {
            Application.Current.Dispatcher.Invoke(() => _overlayWindow.HideOverlay());
            return;
        }

        if (!await _transcriptionGate.WaitAsync(0))
        {
            Application.Current.Dispatcher.Invoke(() => _overlayWindow.HideOverlay());
            return;
        }

        try
        {
            _recordingSession.SetTranscribing();
            Application.Current.Dispatcher.Invoke(() => _overlayWindow.ShowTranscribing());

            string text;
            try
            {
                text = await _whisperService.TranscribeAsync(wavData, _config.ModelVariant, _config.ModelDirectory);
            }
            catch (Exception ex)
            {
                _recordingSession.SetError();
                Application.Current.Dispatcher.Invoke(() =>
                {
                    _overlayWindow.ShowFinalText("Transcription failed. Open settings and verify model/runtime.");
                });
                MessageBox.Show(ex.Message, "Whisper error", MessageBoxButton.OK, MessageBoxImage.Error);
                return;
            }

            Application.Current.Dispatcher.Invoke(() => _overlayWindow.ShowFinalText(text));
            _recordingSession.SetCommitting();
            await _outputService.CommitAsync(text);
            await Task.Delay(180);
            Application.Current.Dispatcher.Invoke(() => _overlayWindow.HideOverlay());
        }
        finally
        {
            _recordingSession.ResetToIdle();
            _transcriptionGate.Release();
        }
    }

    public void Dispose()
    {
        _shortcutService.HotkeyTriggered -= OnHotkeyTriggered;
        _recordingSession?.Dispose();
        _shortcutService.Dispose();
        _audioCapture.Dispose();
        _settingsWindow?.ForceClose();
        _overlayWindow.Close();
        _trayIcon.Visible = false;
        _trayIcon.Dispose();
        _transcriptionGate.Dispose();
    }
}
