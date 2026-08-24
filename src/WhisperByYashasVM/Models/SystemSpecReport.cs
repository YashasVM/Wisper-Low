namespace WhisperByYashasVM.Models;

public sealed class SystemSpecReport
{
    public required bool Is64BitOs { get; init; }
    public required bool IsWindows10OrHigher { get; init; }
    public required int LogicalCpuThreads { get; init; }
    public required double TotalRamGb { get; init; }
    public required double FreeDiskGb { get; init; }
    public required bool Avx2Supported { get; init; }
    public required bool MeetsMinimum { get; init; }
    public required IReadOnlyList<string> FailReasons { get; init; }
}
