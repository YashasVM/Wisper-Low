using System.IO;
using NAudio.Wave;

namespace WhisperByYashasVM.Services;

public sealed class AudioCaptureService : IDisposable
{
    private readonly WaveInEvent _waveIn;
    private MemoryStream _pcmBuffer = new();
    private readonly object _bufferLock = new();
    private bool _capturing;

    public event Action<float>? LevelChanged;

    public AudioCaptureService()
    {
        _waveIn = new WaveInEvent
        {
            WaveFormat = new WaveFormat(16000, 16, 1),
            BufferMilliseconds = 40
        };
        _waveIn.DataAvailable += OnDataAvailable;
    }

    public void StartCapture()
    {
        lock (_bufferLock)
        {
            _pcmBuffer.Dispose();
            _pcmBuffer = new MemoryStream(capacity: 1024 * 128);
            _capturing = true;
        }
        _waveIn.StartRecording();
    }

    public byte[] StopCapture()
    {
        lock (_bufferLock)
        {
            _capturing = false;
        }
        _waveIn.StopRecording();

        byte[] pcm;
        lock (_bufferLock)
        {
            pcm = _pcmBuffer.ToArray();
            _pcmBuffer.Dispose();
            _pcmBuffer = new MemoryStream();
        }

        if (pcm.Length == 0)
        {
            return [];
        }

        using var stream = new MemoryStream();
        using (var writer = new WaveFileWriter(stream, _waveIn.WaveFormat))
        {
            writer.Write(pcm, 0, pcm.Length);
            writer.Flush();
        }

        return stream.ToArray();
    }

    private void OnDataAvailable(object? sender, WaveInEventArgs e)
    {
        lock (_bufferLock)
        {
            if (!_capturing)
            {
                return;
            }
            _pcmBuffer.Write(e.Buffer, 0, e.BytesRecorded);
        }

        float rms = ComputeRms(e.Buffer, e.BytesRecorded);
        LevelChanged?.Invoke(rms);
    }

    private static float ComputeRms(byte[] buffer, int bytesRecorded)
    {
        if (bytesRecorded <= 0)
        {
            return 0f;
        }

        double sumSquares = 0;
        int samples = bytesRecorded / 2;
        for (int i = 0; i < bytesRecorded - 1; i += 2)
        {
            short sample = BitConverter.ToInt16(buffer, i);
            double normalized = sample / 32768.0;
            sumSquares += normalized * normalized;
        }

        return (float)Math.Sqrt(sumSquares / Math.Max(1, samples));
    }

    public void Dispose()
    {
        lock (_bufferLock)
        {
            _pcmBuffer.Dispose();
        }
        _waveIn.Dispose();
    }
}
