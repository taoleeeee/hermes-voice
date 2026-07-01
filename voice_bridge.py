#!/usr/bin/env python3
"""
Hermes Voice Bridge Server (runs on P6P)
=========================================
Accepts audio from P9P voice client, processes through:
  1. STT via Deepgram API (fast, ~0.3-1s) with local whisper.cpp fallback
  2. Chat via Hermes API server (full agent with tools/memory)
  3. TTS via edge-tts native Python lib (fast) with CLI fallback

Endpoints:
  POST /voice       - Send audio, get back audio response
  POST /chat        - Send text, get back text + audio
  GET  /health      - Health check
"""

import asyncio
import json
import logging
import os
import sys
import tempfile
import time
import threading
from http.server import HTTPServer, BaseHTTPRequestHandler
from pathlib import Path
from urllib.parse import urlparse

# ---------------------------------------------------------------------------
# Config
# ---------------------------------------------------------------------------
P6P_HERMES_API = os.getenv("HERMES_API_URL", "http://127.0.0.1:8642")
P6P_API_KEY = os.getenv("API_SERVER_KEY", "")
DEEPGRAM_API_KEY = os.getenv("DEEPGRAM_API_KEY", "")
WHISPER_BIN = os.path.expanduser("~/whisper.cpp/build/bin/whisper-cli")
WHISPER_MODEL = os.path.expanduser("~/whisper.cpp/models/ggml-base.bin")
EDGE_TTS_VOICE = os.getenv("EDGE_TTS_VOICE", "en-AU-NatashaNeural")
LISTEN_HOST = os.getenv("VOICE_BRIDGE_HOST", "0.0.0.0")
LISTEN_PORT = int(os.getenv("VOICE_BRIDGE_PORT", "8700"))

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("voice-bridge")

# ---------------------------------------------------------------------------
# STT via Deepgram API (primary - fast cloud transcription)
# ---------------------------------------------------------------------------
def transcribe_deepgram(audio_path: str) -> str:
    """Transcribe audio using Deepgram Nova-3 API."""
    import urllib.request

    if not DEEPGRAM_API_KEY:
        raise RuntimeError("DEEPGRAM_API_KEY not set")

    with open(audio_path, "rb") as f:
        audio_data = f.read()

    url = "https://api.deepgram.com/v1/listen?model=nova-3&language=en&smart_format=true"
    req = urllib.request.Request(url, data=audio_data, method="POST")
    req.add_header("Authorization", f"Token {DEEPGRAM_API_KEY}")
    req.add_header("Content-Type", "audio/wav")

    with urllib.request.urlopen(req, timeout=15) as resp:
        result = json.loads(resp.read().decode())

    transcript = result.get("results", {})
    channels = transcript.get("channels", [])
    if channels:
        alternatives = channels[0].get("alternatives", [])
        if alternatives:
            return alternatives[0].get("transcript", "").strip()

    return "[STT ERROR: no transcript in response]"


# ---------------------------------------------------------------------------
# STT via local whisper.cpp (fallback)
# ---------------------------------------------------------------------------
def transcribe_local(audio_path: str) -> str:
    """Transcribe audio using local whisper.cpp."""
    import subprocess

    output_dir = tempfile.mkdtemp()
    output_base = os.path.join(output_dir, "transcript")

    # Convert WAV to 16kHz mono if needed (whisper expects 16kHz)
    wav_path = os.path.join(output_dir, "input.wav")
    try:
        subprocess.run(
            ["ffmpeg", "-y", "-i", audio_path, "-ar", "16000", "-ac", "1", wav_path],
            capture_output=True, timeout=10,
        )
    except Exception:
        wav_path = audio_path  # Use original if ffmpeg fails

    try:
        result = subprocess.run(
            [WHISPER_BIN, "-m", WHISPER_MODEL, "-f", wav_path,
             "-l", "en", "-otxt", "-of", output_base, "-np", "-t", "4"],
            capture_output=True, text=True, timeout=60,
        )

        txt_file = output_base + ".txt"
        if os.path.exists(txt_file):
            with open(txt_file) as f:
                return f.read().strip()
        else:
            log.error(f"whisper-cli no output: {result.stderr[:200]}")
            return "[STT ERROR: no output]"
    except subprocess.TimeoutExpired:
        log.error("whisper-cli timed out")
        return "[STT ERROR: timeout]"
    except Exception as e:
        log.error(f"whisper-cli failed: {e}")
        return f"[STT ERROR: {e}]"
    finally:
        try:
            import shutil
            shutil.rmtree(output_dir, ignore_errors=True)
        except Exception:
            pass


# ---------------------------------------------------------------------------
# Chat via Hermes API server
# ---------------------------------------------------------------------------
def chat_hermes(message: str, session_id: str = None) -> str:
    """Send a message to Hermes and get the response."""
    import urllib.request

    url = f"{P6P_HERMES_API}/v1/chat/completions"
    headers = {
        "Content-Type": "application/json",
        "Authorization": f"Bearer {P6P_API_KEY}",
    }
    payload = {
        "model": "hermes-agent",
        "messages": [{"role": "user", "content": message}],
        "stream": False,
    }
    if session_id:
        headers["X-Hermes-Session-Id"] = session_id

    data = json.dumps(payload).encode()
    req = urllib.request.Request(url, data=data, method="POST")
    for k, v in headers.items():
        req.add_header(k, v)

    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            result = json.loads(resp.read().decode())
            choices = result.get("choices", [])
            if choices:
                return choices[0].get("message", {}).get("content", "")
            return "[No response from Hermes]"
    except Exception as e:
        log.error(f"Hermes chat failed: {e}")
        raise


# ---------------------------------------------------------------------------
# Fallback chat via hermes CLI
# ---------------------------------------------------------------------------
def chat_hermes_cli(message: str) -> str:
    """Fallback: use hermes chat -q if API server is unavailable."""
    import subprocess
    try:
        result = subprocess.run(
            ["hermes", "chat", "-q", message],
            capture_output=True, text=True, timeout=120,
        )
        if result.returncode != 0:
            log.error(f"hermes CLI failed: {result.stderr}")
            return "[CLI ERROR]"
        return result.stdout.strip()
    except Exception as e:
        log.error(f"hermes CLI exception: {e}")
        return f"[CLI ERROR: {e}]"


# ---------------------------------------------------------------------------
# TTS via edge-tts (native Python import preferred, CLI fallback)
# ---------------------------------------------------------------------------
def generate_tts(text: str, output_path: str) -> bool:
    """Generate speech audio using edge-tts. Native lib preferred, CLI fallback."""
    # Try native Python import first (faster - no subprocess overhead)
    try:
        import edge_tts
        _generate_tts_native(text, output_path)
        if os.path.exists(output_path) and os.path.getsize(output_path) > 0:
            return True
        log.warning("edge-tts native produced empty file, falling back to CLI")
    except ImportError:
        log.debug("edge-tts Python lib not available, using CLI")
    except Exception as e:
        log.warning(f"edge-tts native failed ({e}), falling back to CLI")

    # Fallback: CLI subprocess
    return _generate_tts_cli(text, output_path)


def _generate_tts_native(text: str, output_path: str) -> None:
    """Generate TTS using edge-tts Python library directly."""
    import edge_tts

    async def _run():
        communicate = edge_tts.Communicate(text, EDGE_TTS_VOICE)
        await communicate.save(output_path)

    asyncio.run(_run())


def _generate_tts_cli(text: str, output_path: str) -> bool:
    """Generate TTS using edge-tts CLI (fallback)."""
    import subprocess
    try:
        result = subprocess.run(
            ["edge-tts", "--voice", EDGE_TTS_VOICE, "--text", text, "--write-media", output_path],
            capture_output=True, text=True, timeout=30,
        )
        return result.returncode == 0 and os.path.exists(output_path)
    except Exception as e:
        log.error(f"edge-tts CLI failed: {e}")
        return False


# ---------------------------------------------------------------------------
# Full pipeline: audio in -> (text, audio_path) out
# ---------------------------------------------------------------------------
def process_voice(audio_data: bytes) -> dict:
    """Full voice pipeline: STT -> Chat -> TTS."""
    timings = {}
    result = {"text_in": "", "text_out": "", "audio_path": None, "timings": timings}

    # Save incoming audio
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as f:
        f.write(audio_data)
        audio_in_path = f.name

    try:
        # Step 1: STT (Deepgram primary, local whisper fallback)
        t0 = time.time()
        user_text = None

        if DEEPGRAM_API_KEY:
            try:
                user_text = transcribe_deepgram(audio_in_path)
                timings["stt_method"] = "deepgram"
            except Exception as e:
                log.warning(f"Deepgram STT failed ({e}), falling back to local whisper")

        if not user_text or user_text.startswith("[STT ERROR"):
            if user_text and user_text.startswith("[STT ERROR"):
                log.warning(f"Deepgram returned error, falling back to local whisper")
            user_text = transcribe_local(audio_in_path)
            timings["stt_method"] = "local_whisper"

        timings["stt"] = round(time.time() - t0, 2)
        result["text_in"] = user_text

        if user_text.startswith("[STT ERROR") or user_text.startswith("[ERROR"):
            result["text_out"] = "Sorry, I couldn't understand the audio."
            return result

        log.info(f"STT ({timings['stt']}s, {timings.get('stt_method', '?')}): {user_text[:80]}")

        # Step 2: Chat
        t0 = time.time()
        try:
            response_text = chat_hermes(user_text)
        except Exception as e:
            log.warning(f"API server failed ({e}), falling back to CLI")
            response_text = chat_hermes_cli(user_text)
        timings["chat"] = round(time.time() - t0, 2)
        result["text_out"] = response_text

        log.info(f"Chat ({timings['chat']}s): {response_text[:80]}")

        # Step 3: TTS
        t0 = time.time()
        audio_out_path = tempfile.mktemp(suffix=".mp3")
        tts_ok = generate_tts(response_text, audio_out_path)
        timings["tts"] = round(time.time() - t0, 2)

        if tts_ok:
            result["audio_path"] = audio_out_path
            log.info(f"TTS ({timings['tts']}s): {audio_out_path}")
        else:
            log.warning("TTS failed, returning text only")

        timings["total"] = round(sum(v for k, v in timings.items() if isinstance(v, (int, float))), 2)
        return result

    finally:
        try:
            os.unlink(audio_in_path)
        except OSError:
            pass


# ---------------------------------------------------------------------------
# HTTP Handler
# ---------------------------------------------------------------------------
class VoiceHandler(BaseHTTPRequestHandler):
    """HTTP request handler with CORS support."""

    def send_cors_headers(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, X-Session-Id, Authorization")
        self.send_header("Access-Control-Expose-Headers", "X-Transcript, X-Response, X-Timings")

    def do_OPTIONS(self):
        """Handle CORS preflight."""
        self.send_response(204)
        self.send_cors_headers()
        self.end_headers()

    def do_GET(self):
        try:
            path = urlparse(self.path).path
            if path == "/health":
                self._handle_health()
            else:
                self.send_response(404)
                self.send_cors_headers()
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(json.dumps({"error": "not found"}).encode())
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception as e:
            log.exception(f"Unhandled error in GET: {e}")

    def do_POST(self):
        try:
            path = urlparse(self.path).path
            if path == "/voice":
                self._handle_voice()
            elif path == "/chat":
                self._handle_chat()
            else:
                self.send_response(404)
                self.send_cors_headers()
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(json.dumps({"error": "not found"}).encode())
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            log.warning("Client disconnected during POST")
        except Exception as e:
            log.exception(f"Unhandled error in POST handler: {e}")
            try:
                self._send_json(500, {"error": str(e)})
            except Exception:
                pass

    def _handle_health(self):
        import urllib.request
        hermes_ok = False
        try:
            req = urllib.request.Request(f"{P6P_HERMES_API}/health")
            with urllib.request.urlopen(req, timeout=3) as resp:
                hermes_ok = resp.status == 200
        except Exception:
            pass

        data = {
            "status": "ok",
            "hermes_api": hermes_ok,
            "deepgram_key": bool(DEEPGRAM_API_KEY),
            "whisper_bin": os.path.exists(WHISPER_BIN),
            "tts_voice": EDGE_TTS_VOICE,
        }
        self.send_response(200)
        self.send_cors_headers()
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(json.dumps(data).encode())

    def _handle_voice(self):
        try:
            content_length = int(self.headers.get("Content-Length", 0))
            if content_length == 0:
                self._send_json(400, {"error": "No audio data"})
                return

            audio_data = self.rfile.read(content_length)
            log.info(f"Received {len(audio_data)} bytes of audio")

            result = process_voice(audio_data)

            def safe(text, max_len=400):
                return text.replace("\n", " ").replace("\r", "")[:max_len]

            if result["audio_path"]:
                audio_bytes = Path(result["audio_path"]).read_bytes()
                try:
                    os.unlink(result["audio_path"])
                except OSError:
                    pass

                self.send_response(200)
                self.send_cors_headers()
                self.send_header("Content-Type", "audio/mpeg")
                self.send_header("X-Transcript", safe(result["text_in"]))
                self.send_header("X-Response", safe(result["text_out"]))
                self.send_header("X-Timings", json.dumps(result["timings"]))
                self.send_header("Content-Length", str(len(audio_bytes)))
                self.end_headers()
                self.wfile.write(audio_bytes)
            else:
                self._send_json(200, {
                    "text_in": result["text_in"],
                    "text_out": result["text_out"],
                    "timings": result["timings"],
                    "has_audio": False,
                })

        except Exception as e:
            log.exception("Voice handler error")
            self._send_json(500, {"error": str(e)})

    def _handle_chat(self):
        try:
            content_length = int(self.headers.get("Content-Length", 0))
            body = json.loads(self.rfile.read(content_length)) if content_length > 0 else {}
            message = body.get("text", "").strip()

            if not message:
                self._send_json(400, {"error": "Missing 'text' field"})
                return

            timings = {}
            t0 = time.time()
            try:
                response_text = chat_hermes(message)
            except Exception as e:
                log.warning(f"API server failed ({e}), falling back to CLI")
                response_text = chat_hermes_cli(message)
            timings["chat"] = round(time.time() - t0, 2)

            t0 = time.time()
            audio_path = tempfile.mktemp(suffix=".mp3")
            tts_ok = generate_tts(response_text, audio_path)
            timings["tts"] = round(time.time() - t0, 2)

            if body.get("audio", True) and tts_ok:
                audio_bytes = Path(audio_path).read_bytes()
                os.unlink(audio_path)
                self.send_response(200)
                self.send_cors_headers()
                self.send_header("Content-Type", "audio/mpeg")
                self.send_header("X-Response", response_text.replace("\n", " ").replace("\r", "")[:400])
                self.send_header("X-Timings", json.dumps(timings))
                self.send_header("Content-Length", str(len(audio_bytes)))
                self.end_headers()
                self.wfile.write(audio_bytes)
            else:
                if tts_ok:
                    os.unlink(audio_path)
                self._send_json(200, {"text": response_text, "timings": timings})

        except Exception as e:
            log.exception("Chat handler error")
            self._send_json(500, {"error": str(e)})

    def _send_json(self, status, data):
        body = json.dumps(data).encode()
        self.send_response(status)
        self.send_cors_headers()
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format, *args):
        """Override to use our logger."""
        log.info(f"{self.client_address[0]} {format % args}")


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
class ReusableHTTPServer(HTTPServer):
    allow_reuse_address = True
    allow_reuse_port = True

def main():
    log.info(f"Voice Bridge starting on {LISTEN_HOST}:{LISTEN_PORT}")
    log.info(f"  Hermes API: {P6P_HERMES_API} (key: {'set' if P6P_API_KEY else 'NOT SET'})")
    log.info(f"  Deepgram STT: {'set' if DEEPGRAM_API_KEY else 'NOT SET (will use local whisper)'}")
    whisper_status = "found" if os.path.exists(WHISPER_BIN) else "NOT FOUND"
    log.info(f"  Local whisper: {whisper_status}")
    log.info(f"  TTS voice: {EDGE_TTS_VOICE}")
    server = ReusableHTTPServer((LISTEN_HOST, LISTEN_PORT), VoiceHandler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        log.info("Shutting down...")
        server.shutdown()


if __name__ == "__main__":
    main()
