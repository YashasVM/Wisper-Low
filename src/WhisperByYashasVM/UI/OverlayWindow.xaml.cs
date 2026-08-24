using System.Windows;
using System.Windows.Media;
using System.Windows.Media.Animation;
using WhisperByYashasVM.Models;
using WhisperByYashasVM.ViewModels;

namespace WhisperByYashasVM.UI;

public partial class OverlayWindow : Window
{
    private readonly OverlayViewModel _vm = new();

    public OverlayWindow()
    {
        InitializeComponent();
        DataContext = _vm;
        Loaded += (_, _) =>
        {
            Reposition();
            Hide();
        };
    }

    public void ShowListening()
    {
        _vm.State = OverlayState.Listening;
        _vm.StatusText = "Listening...";
        _vm.TranscriptText = string.Empty;
        _vm.BubbleWidth = 380;
        TranscriptBlur.Radius = 0;

        Reposition();
        Show();
        AnimateIn();
    }

    public void SetSpeechState(bool speaking)
    {
        if (_vm.State == OverlayState.Transcribing || _vm.State == OverlayState.Finalizing)
        {
            return;
        }

        _vm.State = speaking ? OverlayState.Speaking : OverlayState.Listening;
        _vm.StatusText = speaking ? "Voice detected" : "Listening...";
        AnimateBubbleWidth(speaking ? 480 : 380);
    }

    public void UpdateWaveform(float rms)
    {
        _vm.UpdateWaveform(rms);
    }

    public void ShowTranscribing()
    {
        _vm.State = OverlayState.Transcribing;
        _vm.StatusText = "Transcribing...";
        _vm.TranscriptText = "Processing your speech...";
        _vm.IsTextBlurred = true;
        TranscriptBlur.Radius = 6;
        AnimateBubbleWidth(520);
    }

    public void ShowFinalText(string text)
    {
        _vm.State = OverlayState.Finalizing;
        _vm.StatusText = "Done";
        _vm.TranscriptText = string.IsNullOrWhiteSpace(text) ? "(No speech detected)" : text;
        AnimateBlurToClear();
    }

    public void HideOverlay()
    {
        AnimateOut();
    }

    private void Reposition()
    {
        double screenWidth = SystemParameters.WorkArea.Width;
        double screenHeight = SystemParameters.WorkArea.Height;
        Left = SystemParameters.WorkArea.Left + (screenWidth - Width) / 2;
        Top = SystemParameters.WorkArea.Top + (screenHeight - Height);
    }

    private void AnimateIn()
    {
        var ease = new CubicEase { EasingMode = EasingMode.EaseOut };
        BubbleBorder.BeginAnimation(UIElement.OpacityProperty, new DoubleAnimation(0, 1, TimeSpan.FromMilliseconds(240)) { EasingFunction = ease });
        BubbleTranslate.BeginAnimation(TranslateTransform.YProperty, new DoubleAnimation(80, 0, TimeSpan.FromMilliseconds(260)) { EasingFunction = ease });
    }

    private void AnimateOut()
    {
        var ease = new CubicEase { EasingMode = EasingMode.EaseIn };
        var opacity = new DoubleAnimation(1, 0, TimeSpan.FromMilliseconds(180)) { EasingFunction = ease };
        opacity.Completed += (_, _) => Hide();
        BubbleBorder.BeginAnimation(UIElement.OpacityProperty, opacity);
        BubbleTranslate.BeginAnimation(TranslateTransform.YProperty, new DoubleAnimation(0, 60, TimeSpan.FromMilliseconds(180)) { EasingFunction = ease });
        _vm.State = OverlayState.Hidden;
    }

    private void AnimateBubbleWidth(double targetWidth)
    {
        var ease = new CubicEase { EasingMode = EasingMode.EaseOut };
        var animation = new DoubleAnimation(_vm.BubbleWidth, targetWidth, TimeSpan.FromMilliseconds(220))
        {
            EasingFunction = ease
        };
        BubbleBorder.BeginAnimation(FrameworkElement.WidthProperty, animation);
        _vm.BubbleWidth = targetWidth;
    }

    private void AnimateBlurToClear()
    {
        var ease = new CubicEase { EasingMode = EasingMode.EaseOut };
        TranscriptBlur.BeginAnimation(System.Windows.Media.Effects.BlurEffect.RadiusProperty,
            new DoubleAnimation(8, 0, TimeSpan.FromMilliseconds(200)) { EasingFunction = ease });
    }
}
