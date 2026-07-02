from __future__ import annotations

import argparse
import ctypes
import json
import math
import os
import queue
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import time
import urllib.error
import urllib.request
import wave
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable, Optional

try:
    import tkinter as tk
except Exception as exc:  # pragma: no cover
    raise SystemExit(f"Tkinter is required for the prototype UI: {exc}")

try:
    from PIL import Image, ImageDraw, ImageFont, ImageTk  # type: ignore
except Exception:
    Image = ImageDraw = ImageFont = ImageTk = None

try:
    import keyboard  # type: ignore
except Exception:
    keyboard = None

try:
    import numpy as np  # type: ignore
except Exception:
    np = None

try:
    import pyperclip  # type: ignore
except Exception:
    pyperclip = None

try:
    import sounddevice as sd  # type: ignore
except Exception:
    sd = None


APP_DIR = Path(sys.executable).resolve().parent if getattr(sys, "frozen", False) else Path(__file__).resolve().parent
RESOURCE_DIR = Path(getattr(sys, "_MEIPASS", APP_DIR))
CONFIG_DIR = (
    Path(os.environ.get("LOCALAPPDATA", str(Path.home()))) / "Wisperlow"
    if getattr(sys, "frozen", False)
    else APP_DIR
)
CONFIG_DIR.mkdir(parents=True, exist_ok=True)
CONFIG_PATH = CONFIG_DIR / "wisperlow_config.json"
USAGE_PATH = CONFIG_DIR / "wisperlow_usage.json"

DEFAULT_CONFIG = {
    "toggle_hotkey": "ctrl+alt+p",
    "alternate_toggle_hotkey": "ctrl+alt+space",
    "cancel_hotkey": "ctrl+alt+backspace",
    "sample_rate": 16000,
    "channels": 1,
    "stt_engine": "faster-whisper",
    "stt_model": "small.en",
    "stt_device": "cpu",
    "stt_compute_type": "int8",
    "stt_beam_size": 1,
    "stt_best_of": 1,
    "rewrite_mode": "smart",
    "ollama_enabled": False,
    "ollama_autostart": False,
    "ollama_model": "qwen3:4b",
    "ollama_fallback_model": "qwen3:0.6b",
    "ollama_url": "http://127.0.0.1:11434/api/generate",
    "ollama_ping_timeout_seconds": 0.12,
    "ollama_timeout_seconds": 2.0,
    "ollama_num_predict": 160,
    "ollama_num_ctx": 2048,
    "auto_insert": True,
    "restore_clipboard": True,
    "target_processing_seconds": 2.0,
    "stt_initial_prompt": (
        "Desktop dictation. Prefer clear English words and technical terms: "
        "dictation, detection, edit, improvement, prototype, dashboard, email, grammar, professional."
    ),
    "custom_modes": {
        "email": "Rewrite as a polished professional email or email paragraph.",
        "professional": "Rewrite professionally, clearly, and concisely.",
        "casual": "Rewrite naturally and casually without sounding sloppy.",
        "slack": "Rewrite as a concise workplace chat message.",
        "short": "Rewrite shorter while preserving the intent.",
        "raw": "Only fix punctuation and obvious transcription errors.",
    },
}

DEFAULT_USAGE = {
    "sessions": 0,
    "insertions": 0,
    "cancelled": 0,
    "errors": 0,
    "seconds_recorded": 0.0,
    "words_inserted": 0,
    "last_result": "",
    "last_error": "",
}


@dataclass
class DictationResult:
    raw: str
    final: str
    command: Optional[str] = None
    error: Optional[str] = None
    timings: dict[str, float] = field(default_factory=dict)


@dataclass
class DictationContext:
    window_title: str = ""
    style_hint: str = ""


def load_json(path: Path, default: dict) -> dict:
    if not path.exists():
        path.write_text(json.dumps(default, indent=2), encoding="utf-8")
        return dict(default)
    try:
        existing = json.loads(path.read_text(encoding="utf-8"))
    except json.JSONDecodeError:
        backup = path.with_suffix(".broken.json")
        path.replace(backup)
        path.write_text(json.dumps(default, indent=2), encoding="utf-8")
        return dict(default)
    merged = dict(default)
    merged.update(existing)
    return normalize_config(merged)


def normalize_config(config: dict) -> dict:
    # Migrate early prototype defaults into the fast local path.
    if config.get("toggle_hotkey") in {"ctrl+alt+space", "alt+ctrl+space"}:
        config["toggle_hotkey"] = "ctrl+alt+p"
        config["alternate_toggle_hotkey"] = "ctrl+alt+space"
    bundled_candidates = [
        RESOURCE_DIR / "models" / "faster-whisper-small.en",
        APP_DIR / "_internal" / "models" / "faster-whisper-small.en",
        APP_DIR / "models" / "faster-whisper-small.en",
    ]
    bundled_model = next((path for path in bundled_candidates if ct2_model_ready(path)), None)
    if bundled_model and config.get("stt_model") in {"medium.en", "small.en", "tiny.en", "tiny", "base.en", "base"}:
        config["preferred_stt_model"] = config.get("stt_model", "medium.en")
        config["stt_model"] = str(bundled_model)
    if not hf_ct2_model_cached(str(config.get("stt_model", ""))) and hf_ct2_model_cached("small.en"):
        config["preferred_stt_model"] = config.get("stt_model", "medium.en")
        config["stt_model"] = "small.en"
    if config.get("stt_device") == "auto":
        config["stt_device"] = "cpu"
    if config.get("stt_compute_type") == "auto":
        config["stt_compute_type"] = "int8"
    if int(config.get("stt_beam_size", 1)) > 2:
        config["stt_beam_size"] = 1
    if int(config.get("stt_best_of", 1)) > 2:
        config["stt_best_of"] = 1
    return config


def hf_ct2_model_cached(model_name: str) -> bool:
    if not model_name:
        return False
    model_path = Path(model_name)
    if model_path.exists():
        return ct2_model_ready(model_path)
    if "/" in model_name or "\\" in model_name:
        return False
    model_dir = Path.home() / ".cache" / "huggingface" / "hub" / f"models--Systran--faster-whisper-{model_name}"
    return any(ct2_model_ready(path.parent) for path in model_dir.glob("snapshots/*/model.bin"))


def ct2_model_ready(path: Path) -> bool:
    model_bin = path / "model.bin" if path.is_dir() else path
    try:
        if not model_bin.exists() or model_bin.stat().st_size < 1_000_000:
            return False
        with model_bin.open("rb") as handle:
            return not handle.read(64).startswith(b"version https://git-lfs.github.com/spec")
    except OSError:
        return False


def save_json(path: Path, data: dict) -> None:
    path.write_text(json.dumps(data, indent=2), encoding="utf-8")


def normalize_spaces(text: str) -> str:
    text = re.sub(r"\s+", " ", text).strip()
    return re.sub(r"\s+([,.!?;:])", r"\1", text)


def remove_repetitions(text: str) -> str:
    words = text.split()
    cleaned: list[str] = []
    for word in words:
        low = word.lower()
        if cleaned and cleaned[-1].lower() == low and len(word) > 2:
            continue
        if len(cleaned) >= 2 and cleaned[-1].lower() == low == cleaned[-2].lower():
            continue
        cleaned.append(word)
    return " ".join(cleaned)


def deterministic_cleanup(text: str, mode: str = "smart") -> str:
    text = normalize_spaces(text)
    text = remove_repetitions(text)
    text = re.sub(r"\b(um+|uh+|erm|ah+|like|you know|i mean|basically|actually)\b[,\s]*", "", text, flags=re.I)
    text = re.sub(r"\b(detection app|dictation app|dictation)\s+edit\b", "dictation edit", text, flags=re.I)
    text = re.sub(r"\bdictated as improvement\b", "dictation improvement", text, flags=re.I)
    text = re.sub(r"\bgrammer\b", "grammar", text, flags=re.I)
    replacements = {
        r"\bnew paragraph\b": "\n\n",
        r"\bnew line\b": "\n",
        r"\bcomma\b": ",",
        r"\bperiod\b": ".",
        r"\bfull stop\b": ".",
        r"\bquestion mark\b": "?",
        r"\bexclamation mark\b": "!",
        r"\bcolon\b": ":",
        r"\bsemicolon\b": ";",
    }
    for pattern, replacement in replacements.items():
        text = re.sub(pattern, replacement, text, flags=re.I)
    text = normalize_spaces(text.replace(" \n", "\n").replace("\n ", "\n"))
    if not text:
        return ""
    if mode in {"professional", "email", "slack"}:
        text = re.sub(r"\bcan you\b", "could you please", text, flags=re.I)
        text = re.sub(r"\bi want to\b", "I would like to", text, flags=re.I)
        text = re.sub(r"\bi need to\b", "I need to", text, flags=re.I)
        text = re.sub(r"\bgonna\b", "going to", text, flags=re.I)
        text = re.sub(r"\bwanna\b", "want to", text, flags=re.I)
        text = re.sub(r"\bkinda\b", "somewhat", text, flags=re.I)
        text = re.sub(r"\bsorta\b", "somewhat", text, flags=re.I)
    if mode == "professional":
        text = re.sub(r"\bhey\b", "Hello", text, flags=re.I)
        text = re.sub(r"\bthanks\b", "Thank you", text, flags=re.I)
    if mode == "email":
        text = re.sub(r"^(hey|hello)[,\s]+", "", text, flags=re.I)
    text = text[0].upper() + text[1:]
    if text[-1] not in ".!?:;\n":
        text += "."
    return text


def classify_command(text: str) -> Optional[str]:
    normalized = normalize_spaces(text).lower().strip(" .!?")
    return {
        "delete that": "undo",
        "undo that": "undo",
        "undo last insertion": "undo",
        "new paragraph": "paragraph",
        "new line": "newline",
        "send message": "send",
        "cancel": "cancel",
        "stop": "cancel",
    }.get(normalized)


def looks_like_gibberish(text: str) -> bool:
    compact = re.sub(r"[^a-zA-Z]", "", text)
    if len(compact) < 2:
        return True
    if len(set(compact.lower())) <= 2 and len(compact) > 6:
        return True
    words = text.split()
    return len(words) >= 4 and len(set(w.lower() for w in words)) <= 2


def word_count(text: str) -> int:
    return len(re.findall(r"[A-Za-z0-9']+", text))


def extract_style_directive(text: str, config: dict) -> tuple[str, str]:
    text = re.sub(r"\be[-\s]?mail\b", "email", text, flags=re.I)
    modes = "|".join(re.escape(key) for key in config.get("custom_modes", {}).keys())
    if not modes:
        return text, config.get("rewrite_mode", "smart")
    pattern = rf"(?:\s+|^)(?:/|slash[\s,.:;-]+)({modes})\s*[\.\!]*\s*$"
    match = re.search(pattern, text, flags=re.I)
    if not match:
        natural = re.search(rf"\b(?:make this|write this|format this|turn this into)\s+(?:an?\s+)?({modes})\s*$", text, flags=re.I)
        if not natural:
            return text, config.get("rewrite_mode", "smart")
        return normalize_spaces(text[: natural.start()]), natural.group(1).lower()
    mode = match.group(1).lower()
    return normalize_spaces(text[: match.start()]), mode


def sanitize_llm_output(text: str) -> str:
    text = re.sub(r"<think>.*?</think>", "", text, flags=re.I | re.S)
    text = re.sub(r"^```[a-zA-Z]*|```$", "", text.strip())
    text = re.sub(r"^(final|output|rewritten text)\s*:\s*", "", text, flags=re.I)
    text = text.strip().strip('"').strip("'").strip()
    return normalize_spaces(text)


def infer_surface_from_title(title: str) -> str:
    lowered = title.lower()
    if any(token in lowered for token in ("gmail", "outlook", "mail", "compose")):
        return "email"
    if any(token in lowered for token in ("slack", "teams", "discord", "whatsapp", "telegram")):
        return "chat"
    if any(token in lowered for token in ("word", "docs", "notion", "obsidian")):
        return "document"
    if any(token in lowered for token in ("chrome", "edge", "firefox")):
        return "browser"
    return "text field"


class Bubble:
    IDLE_LEVELS = (0.12, 0.22, 0.16, 0.3, 0.2, 0.36, 0.22, 0.32, 0.18, 0.26, 0.14, 0.2, 0.12)
    ACTIVE_PILL_WIDTH = 96.0

    def __init__(self, root: tk.Tk) -> None:
        self.root = root
        self.window = tk.Toplevel(root)
        self.window.withdraw()
        self.window.overrideredirect(True)
        self.window.attributes("-topmost", True)
        self.transparent = "#ff00ff"
        if sys.platform == "win32":
            self.window.attributes("-transparentcolor", self.transparent)
        self.window.configure(bg=self.transparent)
        self.width = 96
        self.height = 46
        self.pill_width = self.ACTIVE_PILL_WIDTH
        self.target_pill_width = self.ACTIVE_PILL_WIDTH
        self.pill_inset = 2
        self.render_scale = 6
        self.canvas = tk.Label(
            self.window,
            width=self.width,
            height=self.height,
            bg=self.transparent,
            bd=0,
            borderwidth=0,
            highlightthickness=0,
            padx=0,
            pady=0,
            relief="flat",
        )
        self.canvas.pack(fill="both", expand=True)
        self.levels = [0.08] * 13
        self.target_levels = [0.08] * 13
        self.last_level = 0.08
        self.mode = "idle"
        self.detail = ""
        self._image_ref = None
        self._processing_frames: list = []
        self._processing_enter_frames: list = []
        self._processing_enter_started_at = 0.0
        self._draw()
        self._tick()

    def _set_dimensions_for_mode(self, mode: str) -> None:
        if mode == "processing":
            self.target_pill_width = self.ACTIVE_PILL_WIDTH
            self.pill_width = self.ACTIVE_PILL_WIDTH
            self._processing_enter_started_at = time.perf_counter()
        else:
            self.target_pill_width = self.ACTIVE_PILL_WIDTH
        self.canvas.configure(width=self.width, height=self.height)

    def _place(self) -> None:
        x = int((self.window.winfo_screenwidth() - self.width) / 2)
        y = self.window.winfo_screenheight() - self.height - 18
        self.window.geometry(f"{self.width}x{self.height}+{x}+{y}")

    def _draw(self) -> None:
        if Image is None or ImageDraw is None or ImageTk is None:
            return
        if self.mode == "processing":
            elapsed = time.perf_counter() - self._processing_enter_started_at
            if elapsed < 0.22:
                self._draw_cached_processing_enter(elapsed)
                return
            self.pill_width = self.target_pill_width
        if self.mode == "processing" and abs(self.pill_width - self.target_pill_width) < 0.6:
            self._draw_cached_processing()
            return
        fill = "#050505"
        wave = "#ffffff"
        if self.mode == "idle":
            fill = "#ffffff"
            wave = "#050505"
        image, draw, scale, bounds = self._new_pill_frame(fill)
        left, top, right, bottom = bounds
        if self.mode == "processing":
            self._draw_processing(draw, scale, left, top, right, bottom)
        else:
            center = (self.height // 2) * scale
            wave_left = left + 22 * scale
            gap = 4 * scale
            visible_levels = self.levels[-13:]
            if self.mode in {"idle", "error"}:
                visible_levels = self.IDLE_LEVELS
            for i, level in enumerate(visible_levels):
                h = max(5 * scale, int(22 * scale * min(level * 2.2, 1.0)))
                x = wave_left + i * gap
                line_width = max(2 * scale, int(2.4 * scale))
                draw.line((x, center - h // 2, x, center + h // 2), fill=wave, width=line_width)
                r = line_width // 2
                draw.ellipse((x - r, center - h // 2 - r, x + r, center - h // 2 + r), fill=wave)
                draw.ellipse((x - r, center + h // 2 - r, x + r, center + h // 2 + r), fill=wave)
        image = image.resize((self.width, self.height), Image.Resampling.LANCZOS)
        image = self._flatten_for_tk_transparency(image)
        self._image_ref = ImageTk.PhotoImage(image)
        self.canvas.configure(image=self._image_ref)

    def _draw_cached_processing(self) -> None:
        if not self._processing_frames:
            self._processing_frames = [
                ImageTk.PhotoImage(self._render_processing_frame(i / 72 * math.tau))
                for i in range(72)
            ]
        frame = int(time.perf_counter() * 120) % len(self._processing_frames)
        self._image_ref = self._processing_frames[frame]
        self.canvas.configure(image=self._image_ref)

    def _draw_cached_processing_enter(self, elapsed: float) -> None:
        if not self._processing_enter_frames:
            frame_count = 28
            self._processing_enter_frames = [
                ImageTk.PhotoImage(self._render_processing_frame(
                    i / frame_count * math.tau,
                    self.ACTIVE_PILL_WIDTH,
                ))
                for i in range(frame_count)
            ]
        frame = min(len(self._processing_enter_frames) - 1, int((elapsed / 0.22) * len(self._processing_enter_frames)))
        self._image_ref = self._processing_enter_frames[frame]
        self.canvas.configure(image=self._image_ref)

    def _render_processing_frame(self, phase: float, pill_width_override: Optional[float] = None):  # noqa: ANN202
        image, draw, scale, bounds = self._new_pill_frame("#050505", pill_width_override)
        left, top, right, bottom = bounds
        self._draw_processing(draw, scale, left, top, right, bottom, phase)
        image = image.resize((self.width, self.height), Image.Resampling.LANCZOS)
        return self._flatten_for_tk_transparency(image)

    def _new_pill_frame(self, fill: str, pill_width_override: Optional[float] = None):  # noqa: ANN202
        scale = self.render_scale
        image = Image.new("RGBA", (self.width * scale, self.height * scale), (0, 0, 0, 0))
        draw = ImageDraw.Draw(image)
        bounds = self._pill_bounds(scale, pill_width_override)
        left, top, right, bottom = bounds
        draw.rounded_rectangle(bounds, radius=(bottom - top) // 2, fill=fill)
        return image, draw, scale, bounds

    def _pill_bounds(self, scale: int, pill_width_override: Optional[float] = None) -> tuple[int, int, int, int]:
        inset = self.pill_inset * scale
        max_width = (self.width - self.pill_inset * 2) * scale
        requested_width = (pill_width_override if pill_width_override is not None else self.pill_width) * scale
        pill_width = int(min(requested_width, max_width))
        left = ((self.width * scale) - pill_width) // 2
        top = inset
        return left, top, left + pill_width, (self.height * scale) - inset

    def _flatten_for_tk_transparency(self, image):  # noqa: ANN001, ANN202
        # Tk's transparent-color path has no real per-pixel alpha. If we keep
        # antialiased semi-transparent pixels, they blend with magenta and make
        # colored edges. Thresholding in Pillow keeps visible pixels black/white
        # and hidden pixels at the transparent key color without a Python loop.
        key = (255, 0, 255, 255)
        rgba = image.convert("RGBA")
        mask = rgba.getchannel("A").point(lambda alpha: 255 if alpha >= 32 else 0)
        opaque = rgba.copy()
        opaque.putalpha(255)
        flattened = Image.new("RGBA", rgba.size, key)
        flattened.paste(opaque, (0, 0), mask)
        return flattened

    def _draw_processing(
        self,
        draw,
        scale: int,
        left: int,
        top: int,
        right: int,
        bottom: int,
        phase: Optional[float] = None,
    ) -> None:  # noqa: ANN001
        cx = (left + right) // 2
        cy = (top + bottom) // 2
        phase = phase if phase is not None else time.time() * 5.2
        orbit_x = max(8 * scale, (right - left) * 0.16)
        orbit_y = 7.5 * scale
        wash = (math.sin(phase * 0.55) + 1) / 2
        wash_radius = int((10 + wash * 3) * scale)
        wash_x = int(cx + math.cos(phase * 0.45) * 5 * scale)
        wash_y = int(cy + math.sin(phase * 0.42) * 3 * scale)
        draw.ellipse(
            (
                wash_x - wash_radius,
                wash_y - wash_radius,
                wash_x + wash_radius,
                wash_y + wash_radius,
            ),
            fill=(10, 10, 11, 255),
        )
        dot_count = 5
        for i in range(dot_count):
            angle = phase + i * (math.tau / dot_count)
            lead = (math.cos(angle - phase) + 1) / 2
            radius = int((2.25 + lead * 0.75) * scale)
            shade = int(168 + lead * 87)
            x = int(cx + math.cos(angle) * orbit_x)
            y = int(cy + math.sin(angle) * orbit_y)
            draw.ellipse(
                (x - radius, y - radius, x + radius, y + radius),
                fill=(shade, shade, shade, 255),
            )

    def _draw_legacy_canvas(self) -> None:
        if not hasattr(self.canvas, "delete"):
            return
        self.canvas.delete("all")
        fill = "#050505"
        wave = "#ffffff"
        if self.mode == "idle":
            fill = "#ffffff"
            wave = "#050505"
        self._pill(2, 2, self.width - 2, self.height - 2, fill)
        center = self.height // 2
        left = 22
        gap = 4
        visible_levels = self.levels[-13:]
        if self.mode in {"idle", "error"}:
            visible_levels = self.IDLE_LEVELS
        for i, level in enumerate(visible_levels):
            h = max(5, int(22 * min(level * 2.2, 1.0)))
            x = left + i * gap
            self.canvas.create_line(x, center - h // 2, x, center + h // 2, fill=wave, width=2, capstyle="round")

    def _pill(self, x1: int, y1: int, x2: int, y2: int, fill: str) -> None:
        radius = (y2 - y1) // 2
        self.canvas.create_rectangle(x1 + radius, y1, x2 - radius, y2, fill=fill, outline="")
        self.canvas.create_oval(x1, y1, x1 + radius * 2, y2, fill=fill, outline="")
        self.canvas.create_oval(x2 - radius * 2, y1, x2, y2, fill=fill, outline="")

    def _tick(self) -> None:
        if self.window.state() != "withdrawn":
            if self.mode != "processing":
                self.pill_width += (self.target_pill_width - self.pill_width) * 0.18
            self._advance_wave()
            self._draw()
        self.root.after(8 if self.mode == "processing" else 16, self._tick)

    def _advance_wave(self) -> None:
        self.levels = [
            current + (target - current) * 0.28
            for current, target in zip(self.levels, self.target_levels)
        ]

    def set_wave_level(self, level: float) -> None:
        level = max(0.03, min(float(level), 1.0))
        self.last_level = self.last_level * 0.72 + level * 0.28
        self.target_levels.append(self.last_level)
        self.target_levels = self.target_levels[-13:]

    def show(self, mode: str, detail: str = "") -> None:
        self.mode = mode
        self.detail = detail
        self._set_dimensions_for_mode(mode)
        self._place()
        self._draw()
        self.window.deiconify()
        if mode not in {"listening", "processing"}:
            self.hide_later(650)

    def hide(self) -> None:
        self.mode = "hidden"
        self.window.withdraw()

    def hide_later(self, delay_ms: int = 850) -> None:
        self.root.after(delay_ms, self.hide)


class AudioRecorder:
    def __init__(self, config: dict, on_level: Callable[[float], None]) -> None:
        self.config = config
        self.on_level = on_level
        self.frames: list = []
        self.stream = None
        self.started_at = 0.0
        self.is_recording = False

    def start(self) -> None:
        if sd is None or np is None:
            raise RuntimeError("Install audio dependencies with: .\\.venv\\Scripts\\python.exe -m pip install -r requirements.txt")
        self.frames = []
        self.started_at = time.time()
        self.is_recording = True

        def callback(indata, _frames, _time_info, status):  # noqa: ANN001
            if status:
                return
            self.frames.append(indata.copy())
            rms = float(np.sqrt(np.mean(np.square(indata)))) if indata.size else 0.0
            self.on_level(rms * 18)

        self.stream = sd.InputStream(
            samplerate=int(self.config["sample_rate"]),
            channels=int(self.config["channels"]),
            dtype="float32",
            blocksize=256,
            latency="low",
            callback=callback,
        )
        self.stream.start()

    def stop(self) -> tuple[Optional[Path], float]:
        if not self.is_recording:
            return None, 0.0
        self.is_recording = False
        duration = max(0.0, time.time() - self.started_at)
        if self.stream:
            self.stream.stop()
            self.stream.close()
            self.stream = None
        if not self.frames:
            return None, duration
        audio = np.concatenate(self.frames, axis=0)
        audio = self._prepare_audio(audio)
        peak = float(np.max(np.abs(audio))) if audio.size else 0.0
        if peak < 0.006:
            return None, duration
        audio_i16 = (np.clip(audio, -1.0, 1.0) * 32767).astype(np.int16)
        fd, name = tempfile.mkstemp(prefix="wisperlow_", suffix=".wav")
        os.close(fd)
        path = Path(name)
        with wave.open(str(path), "wb") as wav:
            wav.setnchannels(int(self.config["channels"]))
            wav.setsampwidth(2)
            wav.setframerate(int(self.config["sample_rate"]))
            wav.writeframes(audio_i16.tobytes())
        return path, duration

    def _prepare_audio(self, audio):  # noqa: ANN001, ANN202
        if audio.ndim == 2 and audio.shape[1] > 1:
            audio = np.mean(audio, axis=1, keepdims=True)
        flat = audio.reshape(-1)
        if flat.size < 256:
            return audio
        peak = float(np.max(np.abs(flat))) if flat.size else 0.0
        if peak > 0:
            gain = min(4.0, 0.92 / peak)
            flat = flat * gain
        return flat.reshape(-1, 1).astype("float32")

    def cancel(self) -> None:
        if self.stream:
            self.stream.stop()
            self.stream.close()
            self.stream = None
        self.frames = []
        self.is_recording = False


class Transcriber:
    def __init__(self, config: dict, on_status: Callable[[str], None]) -> None:
        self.config = config
        self.on_status = on_status
        self._model = None
        self._ready = threading.Event()
        self._error: Optional[str] = None
        threading.Thread(target=self.warm, daemon=True).start()

    def warm(self) -> None:
        try:
            self._model = self._load_model()
            self._ready.set()
        except Exception as exc:
            self._error = str(exc)
            self._ready.set()

    def _load_model(self):  # noqa: ANN202
        try:
            from faster_whisper import WhisperModel  # type: ignore
        except Exception as exc:
            raise RuntimeError(f"faster-whisper failed to import: {exc}") from exc
        return WhisperModel(
            self.config.get("stt_model", "tiny.en"),
            device=self.config.get("stt_device", "cpu"),
            compute_type=self.config.get("stt_compute_type", "int8"),
            cpu_threads=max(2, min(8, (os.cpu_count() or 4))),
            num_workers=1,
        )

    def transcribe(self, audio_path: Path) -> str:
        if not self._ready.wait(timeout=0.1):
            self.on_status("loading speech model...")
            self._ready.wait(timeout=120.0)
        if self._error:
            raise RuntimeError(
                self._error
                + " | This prototype now forces CPU/int8 to avoid CUDA DLL issues. Restart after installing dependencies."
            )
        if self._model is None:
            self._model = self._load_model()
        segments, _info = self._model.transcribe(
            str(audio_path),
            language="en",
            task="transcribe",
            beam_size=int(self.config.get("stt_beam_size", 5)),
            best_of=int(self.config.get("stt_best_of", 5)),
            patience=1.15,
            repetition_penalty=1.12,
            no_repeat_ngram_size=3,
            compression_ratio_threshold=2.2,
            log_prob_threshold=-0.8,
            no_speech_threshold=0.42,
            vad_filter=False,
            condition_on_previous_text=False,
            without_timestamps=True,
            suppress_blank=True,
            initial_prompt=self.config.get("stt_initial_prompt", ""),
            temperature=0.0,
        )
        transcript_parts = [segment.text.strip() for segment in segments if segment.text and segment.text.strip()]
        return normalize_spaces(" ".join(transcript_parts))


class Rewriter:
    def __init__(self, config: dict) -> None:
        self.config = config
        self._ollama_started = False
        if self.config.get("ollama_enabled", False) and self.config.get("ollama_autostart", True):
            threading.Thread(target=self._ensure_ollama, daemon=True).start()

    def _ensure_ollama(self) -> None:
        if self._ollama_ready():
            return
        exe = shutil.which("ollama")
        if not exe:
            return
        try:
            subprocess.Popen(
                [exe, "serve"],
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                creationflags=subprocess.CREATE_NO_WINDOW if sys.platform == "win32" else 0,
            )
            self._ollama_started = True
        except Exception:
            return

    def _ollama_ready(self) -> bool:
        try:
            with urllib.request.urlopen(
                "http://127.0.0.1:11434/api/tags",
                timeout=float(self.config.get("ollama_ping_timeout_seconds", 0.18)),
            ):
                return True
        except Exception:
            return False

    def _installed_ollama_models(self) -> list[str]:
        try:
            with urllib.request.urlopen(
                "http://127.0.0.1:11434/api/tags",
                timeout=max(0.8, float(self.config.get("ollama_ping_timeout_seconds", 0.5))),
            ) as response:
                data = json.loads(response.read().decode("utf-8"))
        except Exception:
            return []
        return [item.get("name", "") for item in data.get("models", []) if item.get("name")]

    def _rewrite_models(self) -> list[str]:
        installed = set(self._installed_ollama_models())
        preferred = [
            self.config.get("ollama_model", "qwen3:4b"),
            "qwen3:8b",
            "qwen3:4b",
            "llama3.1:8b",
            "llama3.2:3b",
            "gemma3:4b",
            self.config.get("ollama_fallback_model", "qwen3:0.6b"),
            "deepseek-coder:6.7b",
        ]
        selected = []
        for model in preferred:
            if model and model in installed and model not in selected:
                selected.append(model)
        return selected or [self.config.get("ollama_model", "qwen3:4b")]

    def rewrite(self, raw: str, context: DictationContext) -> DictationResult:
        started = time.time()
        command = classify_command(raw)
        if command:
            return DictationResult(raw=raw, final="", command=command, timings={"rewrite": time.time() - started})
        if looks_like_gibberish(raw) and word_count(raw) < 4:
            return DictationResult(raw=raw, final="", error="Did not catch clear speech.")
        stripped_raw, mode = extract_style_directive(raw, self.config)
        context.style_hint = mode
        cleaned = deterministic_cleanup(stripped_raw, mode)
        final = cleaned
        llm_started = time.time()
        if self.config.get("ollama_enabled", False) and self._ollama_ready():
            final = self._try_ollama(stripped_raw, cleaned, context) or cleaned
        if self._final_is_bad(stripped_raw, final, mode):
            final = cleaned if not self._final_is_bad(stripped_raw, cleaned, mode) else ""
        if not final:
            return DictationResult(raw=raw, final="", error="The transcript was too unclear to insert safely.")
        return DictationResult(raw=raw, final=final, timings={"rewrite": time.time() - started, "llm": time.time() - llm_started})

    def _final_is_bad(self, raw: str, final: str, mode: str) -> bool:
        raw_words = word_count(raw)
        final_words = word_count(final)
        if not final.strip() or looks_like_gibberish(final):
            return True
        if raw_words >= 12 and mode != "short" and final_words < max(6, int(raw_words * 0.42)):
            return True
        if re.search(r"\bslash\s+\w+\b|/\w+\b", final, flags=re.I):
            return True
        return False

    def _try_ollama(self, raw: str, cleaned: str, context: DictationContext) -> Optional[str]:
        deadline = time.time() + float(self.config.get("ollama_timeout_seconds", 8.0))
        for model in self._rewrite_models():
            remaining = deadline - time.time()
            if remaining <= 0.2:
                return None
            rewritten = self._call_ollama(model, raw, cleaned, context, remaining)
            if rewritten:
                return rewritten
        return None

    def _call_ollama(self, model: str, raw: str, cleaned: str, context: DictationContext, timeout_seconds: float) -> Optional[str]:
        surface = infer_surface_from_title(context.window_title)
        custom_instruction = self.config.get("custom_modes", {}).get(context.style_hint, "")
        prompt = f"""/no_think
You are Wisperlow's local dictation editor.

Rewrite the FULL transcript into text that can be pasted directly into the user's active {surface}.
Use the active window context only to infer tone and format. Do not invent facts.

Active window: {context.window_title or "unknown"}
Mode: {context.style_hint}
Mode instruction: {custom_instruction or "Fix speech recognition mistakes, grammar, punctuation, and sentence structure."}

Rules:
- Output only the final rewritten text.
- Preserve the user's intent.
- Use the whole transcript, not just the first phrase or last phrase.
- Do not summarize unless the mode is short.
- Correct likely speech-to-text confusions when the intended phrase is obvious.
- Reframe awkward dictated speech into a complete, polished, grammatically correct sentence.
- Prefer clear professional wording over raw transcript wording unless the mode is raw.
- If the transcript contains broken fragments, repair them into the most likely intended sentence.
- Remove filler words, repetitions, and slash commands.
- Do not include explanations, quotes, labels, markdown, or alternatives.
- Never output gibberish or partial fragments.
- If the transcript contains multiple sentences, keep multiple sentences.

Raw transcript:
{raw}

Fast draft:
{cleaned}
"""
        payload = json.dumps(
            {
                "model": model,
                "prompt": prompt,
                "stream": False,
                "keep_alive": "20m",
                "options": {
                    "temperature": 0.0,
                    "num_predict": int(self.config.get("ollama_num_predict", 512)),
                    "num_ctx": int(self.config.get("ollama_num_ctx", 4096)),
                },
            }
        ).encode("utf-8")
        request = urllib.request.Request(
            self.config.get("ollama_url", DEFAULT_CONFIG["ollama_url"]),
            data=payload,
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        try:
            with urllib.request.urlopen(request, timeout=max(0.25, timeout_seconds)) as response:
                data = json.loads(response.read().decode("utf-8"))
        except (urllib.error.URLError, TimeoutError, json.JSONDecodeError, OSError):
            return None
        text = sanitize_llm_output(data.get("response", ""))
        return text if text and not looks_like_gibberish(text) else None


class Inserter:
    def __init__(self, config: dict) -> None:
        self.config = config

    def capture_active_window(self) -> Optional[int]:
        if sys.platform != "win32":
            return None
        try:
            hwnd = ctypes.windll.user32.GetForegroundWindow()
        except Exception:
            return None
        return int(hwnd) if hwnd else None

    def get_window_title(self, hwnd: Optional[int]) -> str:
        if sys.platform != "win32" or not hwnd:
            return ""
        try:
            length = ctypes.windll.user32.GetWindowTextLengthW(hwnd)
            buffer = ctypes.create_unicode_buffer(length + 1)
            ctypes.windll.user32.GetWindowTextW(hwnd, buffer, length + 1)
            return buffer.value.strip()
        except Exception:
            return ""

    def focus_window(self, hwnd: Optional[int]) -> bool:
        if sys.platform != "win32" or not hwnd:
            return False
        try:
            user32 = ctypes.windll.user32
            if not user32.IsWindow(hwnd):
                return False
            if user32.IsIconic(hwnd):
                user32.ShowWindow(hwnd, 9)  # SW_RESTORE
            user32.SetForegroundWindow(hwnd)
            user32.BringWindowToTop(hwnd)
            for _ in range(10):
                if user32.GetForegroundWindow() == hwnd:
                    time.sleep(0.08)
                    return True
                time.sleep(0.03)
        except Exception:
            return False
        return False

    def _copy_for_paste(self, text: str) -> None:
        pyperclip.copy(text)
        deadline = time.time() + 0.8
        while time.time() < deadline:
            try:
                if pyperclip.paste() == text:
                    return
            except Exception:
                pass
            time.sleep(0.03)

    def _send_paste(self) -> None:
        if sys.platform != "win32":
            keyboard.send("ctrl+v")
            return
        user32 = ctypes.windll.user32
        vk_control = 0x11
        vk_v = 0x56
        keyeventf_keyup = 0x0002
        user32.keybd_event(vk_control, 0, 0, 0)
        user32.keybd_event(vk_v, 0, 0, 0)
        user32.keybd_event(vk_v, 0, keyeventf_keyup, 0)
        user32.keybd_event(vk_control, 0, keyeventf_keyup, 0)

    def insert_text(self, text: str, target_hwnd: Optional[int] = None) -> None:
        if pyperclip is None or keyboard is None:
            raise RuntimeError("Install keyboard and pyperclip dependencies.")
        old_clipboard = None
        try:
            old_clipboard = pyperclip.paste()
        except Exception:
            pass
        self._copy_for_paste(text)
        self.focus_window(target_hwnd)
        time.sleep(float(self.config.get("paste_focus_delay_seconds", 0.12)))
        keyboard.release("ctrl")
        keyboard.release("alt")
        keyboard.release("shift")
        self._send_paste()
        if self.config.get("restore_clipboard") and old_clipboard is not None:
            threading.Timer(1.5, lambda: pyperclip.copy(old_clipboard)).start()

    def run_command(self, command: str, target_hwnd: Optional[int]) -> None:
        if keyboard is None:
            raise RuntimeError("Install keyboard dependency.")
        self.focus_window(target_hwnd)
        if command == "undo":
            keyboard.send("ctrl+z")
        elif command == "paragraph":
            self.insert_text("\n\n", target_hwnd)
        elif command == "newline":
            self.insert_text("\n", target_hwnd)
        elif command == "send":
            keyboard.send("enter")


class WisperlowApp:
    def __init__(self) -> None:
        self.config = load_json(CONFIG_PATH, DEFAULT_CONFIG)
        save_json(CONFIG_PATH, self.config)
        self.usage = load_json(USAGE_PATH, DEFAULT_USAGE)
        self.root = tk.Tk()
        self.root.withdraw()
        self.root.title("Wisperlow")
        self.events: queue.Queue[Callable[[], None]] = queue.Queue()
        self.bubble = Bubble(self.root)
        self.recorder = AudioRecorder(self.config, self.bubble.set_wave_level)
        self.transcriber = Transcriber(self.config, lambda text: self.events.put(lambda: self.bubble.show("processing", text)))
        self.rewriter = Rewriter(self.config)
        self.inserter = Inserter(self.config)
        self.processing = False
        self.target_hwnd: Optional[int] = None
        self.context = DictationContext()
        self._register_hotkeys()
        self.root.after(100, self._drain_events)
        self.bubble.hide()

    def _register_hotkeys(self) -> None:
        if keyboard is None:
            self.bubble.show("error", "keyboard missing")
            return
        hotkeys = [self.config["toggle_hotkey"], self.config.get("alternate_toggle_hotkey"), self.config["cancel_hotkey"]]
        for hotkey in [h for h in hotkeys if h]:
            try:
                if hotkey == self.config["cancel_hotkey"]:
                    keyboard.add_hotkey(hotkey, lambda: self.events.put(self.cancel_recording), suppress=True)
                else:
                    keyboard.add_hotkey(hotkey, lambda: self.events.put(self.toggle_recording), suppress=True)
            except Exception:
                self.bubble.show("error", f"{hotkey} busy")
                self.bubble.hide_later(1600)

    def _drain_events(self) -> None:
        while True:
            try:
                callback = self.events.get_nowait()
            except queue.Empty:
                break
            callback()
        self.root.after(50, self._drain_events)

    def toggle_recording(self) -> None:
        if self.processing:
            return
        if self.recorder.is_recording:
            self.stop_recording()
        else:
            self.start_recording()

    def start_recording(self) -> None:
        self.target_hwnd = self.inserter.capture_active_window()
        self.context = DictationContext(window_title=self.inserter.get_window_title(self.target_hwnd))
        try:
            self.recorder.start()
        except Exception as exc:
            self.record_error(str(exc))
            return
        self.usage["sessions"] += 1
        save_json(USAGE_PATH, self.usage)
        self.bubble.show("listening")

    def stop_recording(self) -> None:
        self.processing = True
        self.bubble.show("processing", "polishing text...")
        threading.Thread(target=self._process_recording, daemon=True).start()

    def _process_recording(self) -> None:
        audio_path: Optional[Path] = None
        started = time.time()
        try:
            audio_path, duration = self.recorder.stop()
            self.usage["seconds_recorded"] += duration
            if audio_path is None:
                raise RuntimeError("No speech detected")
            raw = self.transcriber.transcribe(audio_path)
            if not raw:
                raise RuntimeError("No transcript produced")
            result = self.rewriter.rewrite(raw, self.context)
            result.timings["total"] = time.time() - started
            self.events.put(lambda result=result: self._handle_result(result))
        except Exception as exc:
            self.events.put(lambda exc=exc: self.record_error(str(exc)))
        finally:
            self.processing = False
            if audio_path and audio_path.exists():
                try:
                    audio_path.unlink()
                except OSError:
                    pass

    def _handle_result(self, result: DictationResult) -> None:
        if result.error:
            self.record_error(result.error)
            return
        try:
            if result.command:
                self.bubble.hide()
                self.inserter.run_command(result.command, self.target_hwnd)
            else:
                self.bubble.hide()
                self.inserter.insert_text(result.final, self.target_hwnd)
                self.usage["last_result"] = result.final
                self.usage["insertions"] += 1
                self.usage["words_inserted"] += len(result.final.split())
            save_json(USAGE_PATH, self.usage)
            self.bubble.hide()
        except Exception as exc:
            self.record_error(str(exc))

    def cancel_recording(self) -> None:
        self.recorder.cancel()
        self.processing = False
        self.usage["cancelled"] += 1
        save_json(USAGE_PATH, self.usage)
        self.bubble.show("idle", "cancelled")
        self.bubble.hide_later(650)

    def record_error(self, message: str) -> None:
        self.usage["errors"] += 1
        self.usage["last_error"] = message
        save_json(USAGE_PATH, self.usage)
        self.bubble.show("error", message)
        self.bubble.hide_later(2200)

    def run(self) -> None:
        self.root.mainloop()


def self_test() -> int:
    config = load_json(CONFIG_PATH, DEFAULT_CONFIG)
    save_json(CONFIG_PATH, config)
    sample = "um hello there this is is a test new paragraph can you please fix this sentence"
    cleaned = deterministic_cleanup(sample)
    ok = "Hello there" in cleaned and classify_command("delete that") == "undo"
    print("Self-test passed" if ok else "Self-test failed")
    print(f"Hotkey: {config['toggle_hotkey']}")
    print(f"Alternate hotkey: {config.get('alternate_toggle_hotkey')}")
    print(f"STT: {config['stt_model']} on {config['stt_device']} / {config['stt_compute_type']}")
    return 0 if ok else 1


def main() -> int:
    parser = argparse.ArgumentParser(description="Wisperlow local-first dictation bubble")
    parser.add_argument("--self-test", action="store_true", help="run a quick non-audio smoke test")
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    WisperlowApp().run()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
