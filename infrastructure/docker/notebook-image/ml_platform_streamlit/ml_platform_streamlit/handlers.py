import json
import os
import signal
import socket
import subprocess
import time
from datetime import datetime, timezone

import tornado.web

HOME_DIR = "/home/jovyan"
VISUALIZE_DIR = os.path.join(HOME_DIR, "visualize")
BASE_PORT = 8501

# Module-level state for the single managed Streamlit process
_process_state = {
    "process": None,
    "status": "stopped",
    "filePath": None,
    "port": None,
    "errorMessage": None,
}


def _is_port_in_use(port):
    """Check if a TCP port is already in use."""
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        return s.connect_ex(("127.0.0.1", port)) == 0


def _find_available_port():
    """Find an available port starting from BASE_PORT."""
    port = BASE_PORT
    while _is_port_in_use(port):
        port += 1
        if port > BASE_PORT + 100:
            raise RuntimeError("No available port found for Streamlit")
    return port


def _kill_process():
    """Kill the current Streamlit process if running."""
    proc = _process_state["process"]
    if proc is None:
        return
    if proc.poll() is not None:
        # Already dead
        _process_state["process"] = None
        return
    # Send SIGTERM
    try:
        proc.terminate()
    except OSError:
        pass
    # Wait up to 3 seconds for graceful shutdown
    for _ in range(30):
        if proc.poll() is not None:
            break
        time.sleep(0.1)
    # Force kill if still alive
    if proc.poll() is None:
        try:
            proc.kill()
        except OSError:
            pass
        proc.wait(timeout=5)
    _process_state["process"] = None


def _check_process_status():
    """Update process state by checking if the process is still alive."""
    proc = _process_state["process"]
    if proc is None:
        if _process_state["status"] not in ("stopped", "errored"):
            _process_state["status"] = "stopped"
        return
    exit_code = proc.poll()
    if exit_code is not None:
        # Process died
        error_msg = None
        try:
            stderr_output = proc.stderr.read()
            if stderr_output:
                lines = stderr_output.decode("utf-8", errors="replace").strip().splitlines()
                error_msg = "\n".join(lines[-20:])
        except Exception:
            pass
        _process_state["process"] = None
        _process_state["status"] = "errored"
        _process_state["errorMessage"] = error_msg or f"Process exited with code {exit_code}"
    elif _process_state["status"] == "starting":
        # Check if the port is now in use (Streamlit has started serving)
        if _process_state["port"] and _is_port_in_use(_process_state["port"]):
            _process_state["status"] = "running"


def _validate_file_path(file_path):
    """Validate that the file path is safe and within visualize/."""
    if not file_path:
        return False, "filePath is required"
    if ".." in file_path:
        return False, "filePath must not contain '..'"
    if not file_path.startswith("visualize/"):
        return False, "filePath must start with 'visualize/'"
    if not file_path.endswith(".py"):
        return False, "filePath must end with '.py'"
    return True, None


def _has_streamlit_import(file_path):
    """Check if a Python file contains Streamlit imports."""
    try:
        with open(file_path, "r", encoding="utf-8") as f:
            content = f.read()
        return "import streamlit" in content or "from streamlit" in content
    except Exception:
        return False


class StreamlitFilesHandler(tornado.web.RequestHandler):
    """GET /api/streamlit/files — list Streamlit files in visualize/."""

    def set_default_headers(self):
        self.set_header("Content-Type", "application/json")

    def get(self):
        files = []
        if os.path.isdir(VISUALIZE_DIR):
            for root, _dirs, filenames in os.walk(VISUALIZE_DIR):
                for fname in sorted(filenames):
                    if not fname.endswith(".py"):
                        continue
                    full_path = os.path.join(root, fname)
                    if not _has_streamlit_import(full_path):
                        continue
                    rel_path = os.path.relpath(full_path, HOME_DIR)
                    stat = os.stat(full_path)
                    last_modified = datetime.fromtimestamp(
                        stat.st_mtime, tz=timezone.utc
                    ).isoformat()
                    files.append(
                        {
                            "name": fname,
                            "path": rel_path,
                            "lastModified": last_modified,
                        }
                    )
        self.finish(json.dumps({"files": files}))


class StreamlitStartHandler(tornado.web.RequestHandler):
    """POST /api/streamlit/start — start a Streamlit process."""

    def set_default_headers(self):
        self.set_header("Content-Type", "application/json")

    def post(self):
        try:
            body = json.loads(self.request.body)
        except (json.JSONDecodeError, TypeError):
            self.set_status(400)
            self.finish(json.dumps({"error": "Invalid JSON body"}))
            return

        file_path = body.get("filePath", "")
        valid, error = _validate_file_path(file_path)
        if not valid:
            self.set_status(400)
            self.finish(json.dumps({"error": error}))
            return

        abs_path = os.path.join(HOME_DIR, file_path)
        if not os.path.isfile(abs_path):
            self.set_status(404)
            self.finish(json.dumps({"error": f"File not found: {file_path}"}))
            return

        # Kill any existing process
        _kill_process()

        # Find an available port
        try:
            port = _find_available_port()
        except RuntimeError as e:
            self.set_status(500)
            self.finish(json.dumps({"error": str(e)}))
            return

        # Start Streamlit subprocess
        cmd = [
            "streamlit",
            "run",
            abs_path,
            "--server.headless",
            "true",
            "--server.port",
            str(port),
            "--server.address",
            "0.0.0.0",
            "--server.enableCORS",
            "false",
            "--server.enableXsrfProtection",
            "false",
            "--browser.gatherUsageStats",
            "false",
        ]

        try:
            proc = subprocess.Popen(
                cmd,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                cwd=HOME_DIR,
            )
        except Exception as e:
            self.set_status(500)
            self.finish(json.dumps({"error": f"Failed to start Streamlit: {e}"}))
            return

        _process_state["process"] = proc
        _process_state["status"] = "starting"
        _process_state["filePath"] = file_path
        _process_state["port"] = port
        _process_state["errorMessage"] = None

        self.finish(
            json.dumps(
                {
                    "status": "starting",
                    "filePath": file_path,
                    "port": port,
                    "url": None,
                }
            )
        )


class StreamlitStopHandler(tornado.web.RequestHandler):
    """POST /api/streamlit/stop — stop the running Streamlit process."""

    def set_default_headers(self):
        self.set_header("Content-Type", "application/json")

    def post(self):
        _kill_process()
        _process_state["status"] = "stopped"
        _process_state["filePath"] = None
        _process_state["port"] = None
        _process_state["errorMessage"] = None
        self.finish(json.dumps({"status": "stopped"}))


class StreamlitStatusHandler(tornado.web.RequestHandler):
    """GET /api/streamlit/status — get current Streamlit process state."""

    def set_default_headers(self):
        self.set_header("Content-Type", "application/json")

    def get(self):
        _check_process_status()
        self.finish(
            json.dumps(
                {
                    "status": _process_state["status"],
                    "filePath": _process_state["filePath"],
                    "port": _process_state["port"],
                    "url": None,
                    "errorMessage": _process_state["errorMessage"],
                }
            )
        )


def setup_handlers(web_app):
    """Register the Streamlit management API handlers."""
    host_pattern = ".*$"
    base_url = web_app.settings.get("base_url", "/")
    # Normalize base_url to not end with slash for route joining
    base = base_url.rstrip("/")

    handlers = [
        (f"{base}/api/streamlit/files", StreamlitFilesHandler),
        (f"{base}/api/streamlit/start", StreamlitStartHandler),
        (f"{base}/api/streamlit/stop", StreamlitStopHandler),
        (f"{base}/api/streamlit/status", StreamlitStatusHandler),
    ]
    web_app.add_handlers(host_pattern, handlers)
