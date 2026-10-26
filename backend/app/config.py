"""Environment-based configuration. The Sarvam key lives only on the backend."""
import os
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def _load_dotenv(path: Path) -> None:
    # Minimal .env loader (no extra dependency); never overrides real env vars.
    if not path.exists():
        return
    for line in path.read_text(encoding="utf-8").splitlines():
        key, sep, value = line.partition("=")
        if sep and not line.lstrip().startswith("#"):
            os.environ.setdefault(key.strip(), value.strip())


_load_dotenv(ROOT / ".env")

SARVAM_API_KEY = os.environ.get("SARVAM_API_KEY", "")
SARVAM_WS_BASE = os.environ.get("SARVAM_WS_BASE", "wss://api.sarvam.ai")
STT_MODEL = os.environ.get("SCAMSHIELD_STT_MODEL", "saaras:v4")
STT_LANGUAGE = os.environ.get("SCAMSHIELD_STT_LANGUAGE", "auto")
LLM_MODEL = os.environ.get("SCAMSHIELD_LLM_MODEL", "sarvam-105b")
DEV_MODE = os.environ.get("SCAMSHIELD_DEV", "1") == "1"

DEMO_AUDIO_DIR = ROOT / "backend" / "demo_audio"
WEB_DIR = ROOT / "web"


def require_key() -> str:
    if not SARVAM_API_KEY:
        raise RuntimeError("SARVAM_API_KEY is not set (put it in .env at the project root).")
    return SARVAM_API_KEY
