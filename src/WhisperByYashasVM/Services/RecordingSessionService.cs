using System.Diagnostics;
using WhisperByYashasVM.Models;

namespace WhisperByYashasVM.Services;

public sealed class RecordingSessionService : IDisposable
{
    private readonly AudioCaptureService _audioCapture;
    private readonly int _maxRecordingMs;
    private readonly Stopwatch _stopwatch = new();
    private bool _recording;

    public RecordingSessionService(AudioCaptureService audioCapture, int silenceTimeoutMs, int maxRecordingMs)
    {
        _audioCapture = audioCapture;
        _audioCapture.LevelChanged += OnAudioLevelChanged;
        Vad = new VadService(0.02f, silenceTimeoutMs);
        _maxRecordingMs = Math.Max(5000, maxRecordingMs);
    }

    public event Action<float>? AudioLevelChanged;
    public event Action<bool>? SpeechStateChanged;
    public event EventHandler<byte[]>? RecordingCompleted;
    public event Action<RecordingState>? StateChanged;

    public VadService Vad { get; }
    public RecordingState State { get; private set; } = RecordingState.Idle;

    public bool Start()
    {
        if (_recording)
        {
            return false;
        }

        SetState(RecordingState.Arming);
        Vad.Reset();
        _audioCapture.StartCapture();
        _recording = true;
        _stopwatch.Restart();
        SetState(RecordingState.Recording);
        return true;
    }

    public void Stop()
    {
        if (!_recording)
        {
            return;
        }

        _recording = false;
        _stopwatch.Stop();
        var wavData = _audioCapture.StopCapture();
        SetState(RecordingState.Idle);
        RecordingCompleted?.Invoke(this, wavData);
    }

    public void SetTranscribing() => SetState(RecordingState.Transcribing);
    public void SetCommitting() => SetState(RecordingState.Committing);
    public void SetError() => SetState(RecordingState.Error);
    public void ResetToIdle() => SetState(RecordingState.Idle);

    private void OnAudioLevelChanged(float rms)
    {
        if (!_recording)
        {
            return;
        }

        AudioLevelChanged?.Invoke(rms);
        Vad.ProcessFrame(rms, 40);
        SpeechStateChanged?.Invoke(Vad.IsSpeaking);

        if (_stopwatch.ElapsedMilliseconds >= _maxRecordingMs || Vad.ShouldAutoStop)
        {
            Stop();
        }
    }

    private void SetState(RecordingState state)
    {
        if (State == state)
        {
            return;
        }

        State = state;
        StateChanged?.Invoke(state);
    }

    public void Dispose()
    {
        _audioCapture.LevelChanged -= OnAudioLevelChanged;
    }
}
