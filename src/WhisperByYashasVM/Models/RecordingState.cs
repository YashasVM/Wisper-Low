namespace WhisperByYashasVM.Models;

public enum RecordingState
{
    Idle,
    Arming,
    Recording,
    Transcribing,
    Committing,
    Error
}
