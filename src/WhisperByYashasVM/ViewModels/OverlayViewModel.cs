using System.Collections.ObjectModel;
using System.ComponentModel;
using System.Runtime.CompilerServices;
using WhisperByYashasVM.Models;

namespace WhisperByYashasVM.ViewModels;

public sealed class OverlayViewModel : INotifyPropertyChanged
{
    private string _statusText = "Listening...";
    private string _transcriptText = string.Empty;
    private OverlayState _state = OverlayState.Hidden;
    private bool _isTextBlurred = true;
    private double _bubbleWidth = 380;

    public ObservableCollection<double> WaveLevels { get; } =
    [
        8, 10, 12, 8, 14, 11, 9, 13, 10, 8, 12, 11
    ];

    public string StatusText
    {
        get => _statusText;
        set => SetField(ref _statusText, value);
    }

    public string TranscriptText
    {
        get => _transcriptText;
        set => SetField(ref _transcriptText, value);
    }

    public OverlayState State
    {
        get => _state;
        set => SetField(ref _state, value);
    }

    public bool IsTextBlurred
    {
        get => _isTextBlurred;
        set => SetField(ref _isTextBlurred, value);
    }

    public double BubbleWidth
    {
        get => _bubbleWidth;
        set => SetField(ref _bubbleWidth, value);
    }

    public event PropertyChangedEventHandler? PropertyChanged;

    public void UpdateWaveform(float rms)
    {
        double baseHeight = Math.Clamp(rms * 220, 4, 28);
        for (int i = 0; i < WaveLevels.Count; i++)
        {
            double wobble = ((i % 3) - 1) * 1.2;
            WaveLevels[i] = Math.Clamp(baseHeight + wobble, 4, 28);
        }
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(nameof(WaveLevels)));
    }

    private void SetField<T>(ref T field, T value, [CallerMemberName] string? name = null)
    {
        if (EqualityComparer<T>.Default.Equals(field, value))
        {
            return;
        }
        field = value;
        PropertyChanged?.Invoke(this, new PropertyChangedEventArgs(name));
    }
}
