using System.Windows.Forms;

namespace WhisperByYashasVM.Services;

public enum CommitResult
{
    Success,
    Empty,
    Failed
}

public sealed class OutputService
{
    public async Task<CommitResult> CommitAsync(string text, CancellationToken cancellationToken = default)
    {
        if (string.IsNullOrWhiteSpace(text))
        {
            return CommitResult.Empty;
        }

        IDataObject? backupClipboard = null;
        bool backupCaptured = false;

        await System.Windows.Application.Current.Dispatcher.InvokeAsync(() =>
        {
            try
            {
                backupClipboard = Clipboard.GetDataObject();
                backupCaptured = backupClipboard is not null;
            }
            catch
            {
                backupCaptured = false;
            }

            Clipboard.SetText(text);
            SendKeys.SendWait("^v");
        });

        if (backupCaptured && backupClipboard is not null)
        {
            try
            {
                await Task.Delay(300, cancellationToken);
                await System.Windows.Application.Current.Dispatcher.InvokeAsync(() =>
                {
                    Clipboard.SetDataObject(backupClipboard, true);
                });
            }
            catch
            {
                return CommitResult.Failed;
            }
        }

        return CommitResult.Success;
    }
}
