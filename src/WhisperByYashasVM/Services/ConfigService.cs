using System.IO;
using System.Text.Json;
using WhisperByYashasVM.Models;

namespace WhisperByYashasVM.Services;

public sealed class ConfigService
{
    private static readonly JsonSerializerOptions SerializerOptions = new()
    {
        WriteIndented = true
    };

    private readonly string _configPath;

    public ConfigService()
    {
        var appDir = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "WhisperByYashasVM");
        Directory.CreateDirectory(appDir);
        _configPath = Path.Combine(appDir, "config.json");
    }

    public AppConfig Load()
    {
        if (!File.Exists(_configPath))
        {
            return CreateDefault();
        }

        try
        {
            var json = File.ReadAllText(_configPath);
            var config = JsonSerializer.Deserialize<AppConfig>(json);
            if (config is null)
            {
                return CreateDefault();
            }

            if (string.IsNullOrWhiteSpace(config.ModelDirectory))
            {
                config.ModelDirectory = GetDefaultModelDirectory();
            }

            if (config.SilenceTimeoutMs <= 100)
            {
                config.SilenceTimeoutMs = 900;
            }

            if (config.MaxRecordingMs <= 1000)
            {
                config.MaxRecordingMs = 30000;
            }

            return config;
        }
        catch
        {
            return CreateDefault();
        }
    }

    public void Save(AppConfig config)
    {
        if (string.IsNullOrWhiteSpace(config.ModelDirectory))
        {
            config.ModelDirectory = GetDefaultModelDirectory();
        }

        Directory.CreateDirectory(config.ModelDirectory);
        var json = JsonSerializer.Serialize(config, SerializerOptions);
        File.WriteAllText(_configPath, json);
    }

    public static string GetDefaultModelDirectory()
    {
        var modelDir = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
            "WhisperByYashasVM",
            "models");
        Directory.CreateDirectory(modelDir);
        return modelDir;
    }

    private static AppConfig CreateDefault()
    {
        return new AppConfig
        {
            ModelDirectory = GetDefaultModelDirectory()
        };
    }
}
