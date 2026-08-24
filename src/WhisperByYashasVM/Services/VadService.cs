namespace WhisperByYashasVM.Services;

public sealed class VadService
{
    private readonly float _speechThreshold;
    private readonly int _silenceTimeoutMs;
    private bool _speechStarted;
    private int _silenceMs;

    public VadService(float speechThreshold, int silenceTimeoutMs)
    {
        _speechThreshold = speechThreshold;
        _silenceTimeoutMs = Math.Max(250, silenceTimeoutMs);
    }

    public bool IsSpeaking { get; private set; }
    public bool ShouldAutoStop { get; private set; }

    public void Reset()
    {
        _speechStarted = false;
        _silenceMs = 0;
        IsSpeaking = false;
        ShouldAutoStop = false;
    }

    public void ProcessFrame(float rms, int frameDurationMs)
    {
        var speaking = rms >= _speechThreshold;
        IsSpeaking = speaking;

        if (speaking)
        {
            _speechStarted = true;
            _silenceMs = 0;
            ShouldAutoStop = false;
            return;
        }

        if (!_speechStarted)
        {
            ShouldAutoStop = false;
            return;
        }

        _silenceMs += Math.Max(1, frameDurationMs);
        ShouldAutoStop = _silenceMs >= _silenceTimeoutMs;
    }
}
