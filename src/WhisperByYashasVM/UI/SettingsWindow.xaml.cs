using System.Diagnostics;
using System.IO;
using System.Windows;
using WhisperByYashasVM.Models;
using WhisperByYashasVM.Services;
using MessageBox = System.Windows.MessageBox;

namespace WhisperByYashasVM.UI;

public partial class SettingsWindow : Window
{
    private readonly ConfigService _configService;
    private readonly StartupRegistrationService _startupRegistrationService;
    private readonly Action<AppConfig> _onConfigUpdated;
    private bool _allowClose;
    private AppConfig _config;

    public SettingsWindow(
        AppConfig initialConfig,
        ConfigService configService,
        StartupRegistrationService startupRegistrationService,
        Action<AppConfig> onConfigUpdated)
    {
        InitializeComponent();
        _config = initialConfig;
        _configService = configService;
        _startupRegistrationService = startupRegistrationService;
        _onConfigUpdated = onConfigUpdated;

        Closing += OnClosing;
        BrowseButton.Click += OnBrowseClicked;
        SaveButton.Click += OnSaveClicked;
        LoadConfigToView();
    }

    public void ApplyConfig(AppConfig config)
    {
        _config = config;
        LoadConfigToView();
    }

    public void ForceClose()
    {
        _allowClose = true;
        Close();
    }

    private void OnSaveClicked(object? sender, RoutedEventArgs e)
    {
        if (!int.TryParse(SilenceTimeoutTextBox.Text.Trim(), out var silenceTimeout) || silenceTimeout < 250 || silenceTimeout > 5000)
        {
            MessageBox.Show(this, "Silence timeout must be between 250 and 5000 ms.", "Invalid value", MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }

        var modelPath = ModelPathTextBox.Text.Trim();
        if (string.IsNullOrWhiteSpace(modelPath))
        {
            MessageBox.Show(this, "Model path is required.", "Invalid path", MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }

        try
        {
            Directory.CreateDirectory(modelPath);
        }
        catch (Exception ex)
        {
            MessageBox.Show(this, ex.Message, "Path error", MessageBoxButton.OK, MessageBoxImage.Error);
            return;
        }

        _config.ModelVariant = ModelVariantComboBox.SelectedIndex == 1 ? "small.en" : "base.en";
        _config.ModelDirectory = modelPath;
        _config.SilenceTimeoutMs = silenceTimeout;
        _config.StartWithWindows = StartWithWindowsCheckBox.IsChecked == true;

        _configService.Save(_config);
        var exePath = Process.GetCurrentProcess().MainModule?.FileName ?? string.Empty;
        if (!string.IsNullOrWhiteSpace(exePath))
        {
            _startupRegistrationService.Apply(_config.StartWithWindows, exePath);
        }

        _onConfigUpdated(_config);
        MessageBox.Show(this, "Settings saved.", "Whisper", MessageBoxButton.OK, MessageBoxImage.Information);
    }

    private void OnBrowseClicked(object? sender, RoutedEventArgs e)
    {
        using var dialog = new System.Windows.Forms.FolderBrowserDialog
        {
            InitialDirectory = ModelPathTextBox.Text
        };
        if (dialog.ShowDialog() == System.Windows.Forms.DialogResult.OK)
        {
            ModelPathTextBox.Text = dialog.SelectedPath;
        }
    }

    private void LoadConfigToView()
    {
        ModelVariantComboBox.SelectedIndex = _config.ModelVariant.Equals("small.en", StringComparison.OrdinalIgnoreCase) ? 1 : 0;
        ModelPathTextBox.Text = _config.ModelDirectory;
        SilenceTimeoutTextBox.Text = _config.SilenceTimeoutMs.ToString();
        StartWithWindowsCheckBox.IsChecked = _config.StartWithWindows;
    }

    private void OnClosing(object? sender, System.ComponentModel.CancelEventArgs e)
    {
        if (_allowClose)
        {
            return;
        }

        e.Cancel = true;
        Hide();
    }
}
