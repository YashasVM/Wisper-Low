using System.IO;
using System.Windows;
using System.Windows.Controls;
using WhisperByYashasVM.Models;
using WhisperByYashasVM.Services;
using MessageBox = System.Windows.MessageBox;

namespace WhisperByYashasVM.UI;

public partial class SetupWindow : Window
{
    private readonly WhisperService _whisperService;
    private readonly AppConfig _config;
    private readonly SystemSpecReport _specReport;

    public SetupWindow(AppConfig config, SystemSpecReport specReport, WhisperService whisperService)
    {
        InitializeComponent();
        _config = config;
        _specReport = specReport;
        _whisperService = whisperService;

        ModelPathTextBox.Text = string.IsNullOrWhiteSpace(_config.ModelDirectory)
            ? ConfigService.GetDefaultModelDirectory()
            : _config.ModelDirectory;
        StartWithWindowsCheckBox.IsChecked = _config.StartWithWindows;
        ModelVariantComboBox.SelectedIndex = _config.ModelVariant.Equals("small.en", StringComparison.OrdinalIgnoreCase) ? 1 : 0;

        if (!_specReport.MeetsMinimum)
        {
            WarningBorder.Visibility = Visibility.Visible;
            WarningText.Text = string.Join(Environment.NewLine, _specReport.FailReasons);
            ContinueButton.IsEnabled = false;
        }

        ContinueAnywayCheckBox.Checked += (_, _) => ContinueButton.IsEnabled = true;
        ContinueAnywayCheckBox.Unchecked += (_, _) =>
        {
            if (!_specReport.MeetsMinimum)
            {
                ContinueButton.IsEnabled = false;
            }
        };

        BrowseButton.Click += OnBrowseClicked;
        DownloadButton.Click += OnDownloadClicked;
        ContinueButton.Click += OnContinueClicked;
    }

    public AppConfig? ResultConfig { get; private set; }

    private async void OnDownloadClicked(object? sender, RoutedEventArgs e)
    {
        var variant = GetSelectedVariant();
        var modelPath = ModelPathTextBox.Text.Trim();
        if (string.IsNullOrWhiteSpace(modelPath))
        {
            MessageBox.Show(this, "Choose a valid model path.", "Invalid path", MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }

        try
        {
            Directory.CreateDirectory(modelPath);
            DownloadButton.IsEnabled = false;
            ModelProgressText.Text = "Downloading model...";
            var progress = new Progress<double>(value =>
            {
                ModelProgressBar.Value = Math.Clamp(value * 100, 0, 100);
                ModelProgressText.Text = $"Downloading model... {ModelProgressBar.Value:0}%";
            });
            await _whisperService.EnsureModelAsync(variant, modelPath, progress);
            ModelProgressText.Text = "Model download complete.";
        }
        catch (Exception ex)
        {
            ModelProgressText.Text = "Model download failed.";
            MessageBox.Show(this, ex.Message, "Download error", MessageBoxButton.OK, MessageBoxImage.Error);
        }
        finally
        {
            DownloadButton.IsEnabled = true;
        }
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

    private void OnContinueClicked(object? sender, RoutedEventArgs e)
    {
        var modelPath = ModelPathTextBox.Text.Trim();
        if (string.IsNullOrWhiteSpace(modelPath))
        {
            MessageBox.Show(this, "Model path is required.", "Invalid path", MessageBoxButton.OK, MessageBoxImage.Warning);
            return;
        }

        ResultConfig = new AppConfig
        {
            FirstRunCompleted = true,
            AllowUnsupportedDevice = _specReport.MeetsMinimum || ContinueAnywayCheckBox.IsChecked == true,
            StartWithWindows = StartWithWindowsCheckBox.IsChecked == true,
            ModelVariant = GetSelectedVariant(),
            ModelDirectory = modelPath,
            SilenceTimeoutMs = _config.SilenceTimeoutMs,
            MaxRecordingMs = _config.MaxRecordingMs
        };

        DialogResult = true;
        Close();
    }

    private string GetSelectedVariant()
    {
        return (ModelVariantComboBox.SelectedIndex == 1) ? "small.en" : "base.en";
    }
}
