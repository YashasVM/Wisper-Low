using System.IO;
using System.Net.Http;
using Whisper.net;

namespace WhisperByYashasVM.Services;

public sealed class WhisperService
{
    private static readonly HttpClient HttpClient = new();
    private readonly SemaphoreSlim _downloadLock = new(1, 1);

    public static IReadOnlyDictionary<string, string> SupportedModels { get; } = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase)
    {
        ["base.en"] = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-base.en.bin",
        ["small.en"] = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-small.en.bin"
    };

    public string ResolveModelFilePath(string modelVariant, string modelDirectory)
    {
        var normalizedVariant = NormalizeVariant(modelVariant);
        return Path.Combine(modelDirectory, $"ggml-{normalizedVariant}.bin");
    }

    public async Task EnsureModelAsync(
        string modelVariant,
        string modelDirectory,
        IProgress<double>? progress = null,
        CancellationToken cancellationToken = default)
    {
        var normalizedVariant = NormalizeVariant(modelVariant);
        if (!SupportedModels.TryGetValue(normalizedVariant, out var url))
        {
            throw new InvalidOperationException($"Unsupported model variant: {modelVariant}");
        }

        Directory.CreateDirectory(modelDirectory);
        var targetPath = ResolveModelFilePath(normalizedVariant, modelDirectory);
        if (File.Exists(targetPath))
        {
            progress?.Report(1d);
            return;
        }

        await _downloadLock.WaitAsync(cancellationToken);
        try
        {
            if (File.Exists(targetPath))
            {
                progress?.Report(1d);
                return;
            }

            var tempPath = targetPath + ".download";
            using var response = await HttpClient.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
            response.EnsureSuccessStatusCode();
            var totalLength = response.Content.Headers.ContentLength;

            await using var source = await response.Content.ReadAsStreamAsync(cancellationToken);
            await using var destination = File.Create(tempPath);

            var buffer = new byte[64 * 1024];
            long totalRead = 0;
            int read;
            while ((read = await source.ReadAsync(buffer, cancellationToken)) > 0)
            {
                await destination.WriteAsync(buffer.AsMemory(0, read), cancellationToken);
                totalRead += read;
                if (totalLength.HasValue && totalLength.Value > 0)
                {
                    progress?.Report((double)totalRead / totalLength.Value);
                }
            }

            destination.Flush();
            File.Move(tempPath, targetPath, overwrite: true);
            progress?.Report(1d);
        }
        finally
        {
            _downloadLock.Release();
        }
    }

    public async Task<string> TranscribeAsync(
        byte[] wavData,
        string modelVariant,
        string modelDirectory,
        CancellationToken cancellationToken = default)
    {
        await EnsureModelAsync(modelVariant, modelDirectory, cancellationToken: cancellationToken);
        var modelPath = ResolveModelFilePath(modelVariant, modelDirectory);
        var builder = WhisperFactory.FromPath(modelPath).CreateBuilder();
        using var processor = builder.WithLanguage("en").Build();
        await using var audioStream = new MemoryStream(wavData);

        var segments = new List<string>();
        await foreach (var segment in processor.ProcessAsync(audioStream).WithCancellation(cancellationToken))
        {
            if (!string.IsNullOrWhiteSpace(segment.Text))
            {
                segments.Add(segment.Text.Trim());
            }
        }

        return string.Join(" ", segments).Trim();
    }

    private static string NormalizeVariant(string modelVariant)
    {
        var normalized = string.IsNullOrWhiteSpace(modelVariant) ? "base.en" : modelVariant.Trim().ToLowerInvariant();
        return SupportedModels.ContainsKey(normalized) ? normalized : "base.en";
    }
}
