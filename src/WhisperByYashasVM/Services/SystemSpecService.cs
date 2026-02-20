using System.IO;
using System.Runtime.Intrinsics.X86;
using Microsoft.VisualBasic.Devices;
using WhisperByYashasVM.Models;

namespace WhisperByYashasVM.Services;

public sealed class SystemSpecService
{
    public SystemSpecReport Evaluate(string modelDirectory)
    {
        var failReasons = new List<string>();
        var os = Environment.OSVersion.Version;
        var is64Bit = Environment.Is64BitOperatingSystem;
        var isWindows10OrHigher = os.Major >= 10;
        var cpuThreads = Environment.ProcessorCount;
        var totalRamGb = Math.Round(new ComputerInfo().TotalPhysicalMemory / 1024d / 1024d / 1024d, 2);
        var freeDiskGb = Math.Round(GetFreeDiskGb(modelDirectory), 2);
        var avx2 = Avx2.IsSupported;

        if (!is64Bit || !isWindows10OrHigher)
        {
            failReasons.Add("Requires Windows 10/11 64-bit.");
        }

        if (cpuThreads < 4)
        {
            failReasons.Add("Requires at least 4 logical CPU threads.");
        }

        if (totalRamGb < 8)
        {
            failReasons.Add("Requires at least 8 GB RAM.");
        }

        if (!avx2)
        {
            failReasons.Add("Requires AVX2 CPU support.");
        }

        if (freeDiskGb < 2)
        {
            failReasons.Add("Requires at least 2 GB free disk space.");
        }

        return new SystemSpecReport
        {
            Is64BitOs = is64Bit,
            IsWindows10OrHigher = isWindows10OrHigher,
            LogicalCpuThreads = cpuThreads,
            TotalRamGb = totalRamGb,
            FreeDiskGb = freeDiskGb,
            Avx2Supported = avx2,
            MeetsMinimum = failReasons.Count == 0,
            FailReasons = failReasons
        };
    }

    private static double GetFreeDiskGb(string modelDirectory)
    {
        try
        {
            var root = Path.GetPathRoot(modelDirectory);
            if (string.IsNullOrWhiteSpace(root))
            {
                return 0;
            }

            var drive = new DriveInfo(root);
            return drive.AvailableFreeSpace / 1024d / 1024d / 1024d;
        }
        catch
        {
            return 0;
        }
    }
}
