namespace WhisperByYashasVM.Models;

public sealed class AppConfig
{
    public bool FirstRunCompleted { get; set; }
    public bool AllowUnsupportedDevice { get; set; }
    public bool StartWithWindows { get; set; }
    public string ModelVariant { get; set; } = "base.en";
    public string ModelDirectory { get; set; } = string.Empty;
    public int SilenceTimeoutMs { get; set; } = 900;
    public int MaxRecordingMs { get; set; } = 30000;
}
