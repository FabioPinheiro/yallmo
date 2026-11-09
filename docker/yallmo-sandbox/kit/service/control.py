#!/usr/bin/env python3
"""Control the Sandbox's Yallmo process through Supervisor; never kill arbitrary PIDs or port owners."""
import argparse
import http.client
import os
from pathlib import Path
import subprocess
import sys
import time
import urllib.error
import urllib.request

CONFIG = Path(__file__).resolve().with_name("supervisord.conf")


def environment():
    workspace = Path(os.environ.get("YALLMO_WORKSPACE", "/home/agent/yallmo-workspace")).resolve()
    if not workspace.is_dir():
        raise ValueError("Yallmo workspace is missing")
    state = Path(os.environ.get("XDG_STATE_HOME", str(Path.home() / ".local/state"))) / "yallmo-service"
    logs = workspace / "logs"
    value = os.environ.get("YALLMO_AUTOSTART", "1").lower()
    if value not in {"1", "true", "yes", "on", "0", "false", "no", "off"}:
        raise ValueError("YALLMO_AUTOSTART must be 1 or 0")
    os.umask(0o077)
    state.mkdir(parents=True, exist_ok=True, mode=0o700)
    logs.mkdir(parents=True, exist_ok=True, mode=0o700)
    return dict(os.environ, YALLMO_SERVICE_WORKSPACE=str(workspace), YALLMO_SERVICE_STATE=str(state),
                YALLMO_SERVICE_LOG_DIR=str(logs),
                YALLMO_SERVICE_AUTOSTART="true" if value in {"1", "true", "yes", "on"} else "false")


def ctl(env, *arguments):
    return subprocess.run(["supervisorctl", "-c", str(CONFIG), *arguments], env=env,
                          cwd=env["YALLMO_SERVICE_STATE"], text=True, capture_output=True, timeout=35)


def show(result):
    if result.stdout:
        print(result.stdout.rstrip())
    if result.stderr:
        print(result.stderr.rstrip(), file=sys.stderr)


def state(result):
    words = result.stdout.split()
    return words[1] if len(words) >= 2 and words[0] == "yallmo" else None


def manager_running(env):
    result = ctl(env, "pid")
    return result.returncode == 0 and result.stdout.strip().isdigit()


def ensure_manager(env):
    if manager_running(env):
        return True
    # Manual recovery if the boot-time manager has exited. This daemonizes, not the service itself.
    manual = dict(env, YALLMO_SERVICE_AUTOSTART="false")
    result = subprocess.run(["supervisord", "-c", str(CONFIG)], env=manual,
                            cwd=env["YALLMO_SERVICE_STATE"], text=True, capture_output=True, timeout=10)
    for _ in range(25):
        if manager_running(env):
            return True
        time.sleep(0.1)
    show(result)
    print("Yallmo service manager did not start; inspect logs/supervisor.log.", file=sys.stderr)
    return False


def web_ready(url):
    # The local website must not go through the provider credential-injection proxy.
    try:
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        with opener.open(url, timeout=1) as response:
            response.read(65536)
            return 200 <= response.status < 300
    except (OSError, urllib.error.URLError, http.client.HTTPException):
        return False


def status(env, wait=False):
    deadline = time.monotonic() + (30 if wait else 0)
    try:
        port = int(env.get("YALLMO_SERVICE_PORT", "8080"))
    except ValueError:
        raise ValueError("YALLMO_SERVICE_PORT must be a port number") from None
    if not 1 <= port <= 65535:
        raise ValueError("YALLMO_SERVICE_PORT must be between 1 and 65535")
    url = f"http://127.0.0.1:{port}/"
    while True:
        result = ctl(env, "status", "yallmo")
        current = state(result)
        ready = current == "RUNNING" and web_ready(url)
        initial = current == "STOPPED" and "Not started" in result.stdout and env["YALLMO_SERVICE_AUTOSTART"] == "true"
        pending = current in {None, "STARTING", "BACKOFF", "RUNNING"} or initial
        if wait and pending and not ready and time.monotonic() < deadline:
            time.sleep(0.2)
            continue
        show(result)
        print(f"website:       {url} ({'responding' if ready else 'not ready'})")
        print("stdout/stderr: " + str(Path(env["YALLMO_SERVICE_LOG_DIR"]) / "yallmo.log"))
        print("manager log:   " + str(Path(env["YALLMO_SERVICE_LOG_DIR"]) / "supervisor.log"))
        if current is None:
            print("Service manager unavailable; use yallmo-service start to recover.", file=sys.stderr)
        return 0 if ready else 3


def control(env, action):
    if action == "stop" and not manager_running(env):
        print("No service manager is running; no processes were signaled.")
        return 0
    if not ensure_manager(env):
        return 1
    if action == "restart":
        stopped = control(env, "stop")
        return control(env, "start") if stopped == 0 else stopped
    current = ctl(env, "status", "yallmo")
    if action == "start" and state(current) in {"STARTING", "RUNNING"}:
        return status(env, wait=True)
    if action == "stop" and state(current) in {"STOPPED", "EXITED", "FATAL"}:
        show(current)
        return 0
    result = ctl(env, action, "yallmo")
    show(result)
    if result.returncode:
        print("Inspect startup errors with: yallmo-service logs", file=sys.stderr)
        return 1
    return status(env, wait=True) if action == "start" else 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("start", help="Start Yallmo (or recover its manager)")
    commands.add_parser("stop", help="Gracefully stop only the managed Yallmo process group")
    commands.add_parser("restart", help="Stop and start the managed Yallmo process")
    status_parser = commands.add_parser("status", help="Show process state and log paths")
    status_parser.add_argument("--wait", action="store_true", help="Wait up to 30 seconds for boot and the local website")
    log_parser = commands.add_parser("logs", help="Inspect combined stdout/stderr, including rotated-log following")
    log_parser.add_argument("--follow", "-f", action="store_true")
    log_parser.add_argument("--lines", "-n", type=int, default=100)
    log_parser.add_argument("--supervisor", action="store_true", help="Inspect the manager log instead")
    commands.add_parser("supervise", help="Run the service manager in the foreground (Sandbox boot hook)")
    args = parser.parse_args()
    if args.command == "logs" and args.lines < 0:
        parser.error("--lines must be non-negative")
    try:
        env = environment()
        if args.command == "supervise":
            if manager_running(env):
                print("Yallmo service manager is already running.")
                return 0
            if not (Path(env["YALLMO_SERVICE_WORKSPACE"]) / "server.sh").is_file():
                raise ValueError(f"Yallmo launcher is missing: {env['YALLMO_SERVICE_WORKSPACE']}/server.sh")
            os.execvpe("supervisord", ["supervisord", "-n", "-c", str(CONFIG)], env)
        if args.command == "status":
            return status(env, args.wait)
        if args.command == "logs":
            path = Path(env["YALLMO_SERVICE_LOG_DIR"]) / ("supervisor.log" if args.supervisor else "yallmo.log")
            print(str(path), file=sys.stderr)
            if not path.exists() and not args.follow:
                print("No logs yet.")
                return 0
            arguments = ["tail", "-n", str(args.lines)] + (["-F"] if args.follow else []) + ["--", str(path)]
            os.execvpe("tail", arguments, env)
        return control(env, args.command)
    except (OSError, ValueError, subprocess.SubprocessError) as error:
        print(f"Yallmo service control failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
