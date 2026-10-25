#!/usr/bin/env python3
"""隔离验证实际发行 JAR/Node：本地 Anthropic 端点、UDS、控制 PTY 与真实 shell 作业控制。

不读取用户环境/配置/认证；所有业务文件和 HOME 均在临时目录。报告只保留夹具结果，
绝不记录 HTTP 认证头。默认要求已有成套产物；构建与验收分开，避免旧产物假通过。
"""
import argparse
import fcntl
import hashlib
import http.server
import json
import os
from pathlib import Path
import pty
import re
import select
import shlex
import shutil
import signal
import struct
import sys
import subprocess
import tempfile
import termios
import threading
import time
import zipfile

REPO = Path(__file__).resolve().parent.parent
ANSI = re.compile(rb"\x1b\[[0-?]*[ -/]*[@-~]|\x1b\][^\x07]*(?:\x07|\x1b\\)")


class Endpoint(http.server.BaseHTTPRequestHandler):
    """只服务本次夹具；同一工具结果只发一次最终回答，不接触真实模型。"""

    def log_message(self, *_args):
        pass

    def do_POST(self):
        body = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        self.server.calls.append(body)
        if body.get("tools") and self.server.scenario in ("model-error", "retry"):
            if self.server.scenario == "model-error" or not self.server.failed_once:
                self.server.failed_once = True
                error = json.dumps({"type": "error", "error": {"type": "overloaded_error" if self.server.scenario == "retry" else "invalid_request_error", "message": "LOCAL_MODEL_FAILURE"}}).encode()
                self.send_response(503 if self.server.scenario == "retry" else 400)
                self.send_header("Content-Type", "application/json"); self.send_header("Content-Length", str(len(error)))
                self.end_headers(); self.wfile.write(error); return
        messages = body.get("messages", [])
        text = json.dumps(messages, ensure_ascii=False)
        results = [block for message in messages for block in message.get("content", [])
                   if isinstance(block, dict) and block.get("type") == "tool_result"]
        tool = "JADE_VERIFY_TOOL" in text and not results
        requested = None
        if "JADE_VERIFY_MCP" in text and len(results) < 2:
            requested = ("connect_mcp", {"name": "fixture"}) if not results else ("mcp__fixture__echo", {"text": "MCP_ECHO_OK"})
        if "JADE_VERIFY_TEAM" in text and len(results) < 2:
            requested = ("spawn_teammate", {"role": "local verifier"}) if not results else ("list_teammates", {})
        tool = tool or requested is not None
        if tool:
            content = [{"type": "tool_use", "id": "verify-write", "name": "write_file",
                        "input": {"path": str(self.server.target), "content": "APPROVAL_OK"}}]
            if requested:
                content = [{"type": "tool_use", "id": "verify-" + requested[0], "name": requested[0], "input": requested[1]}]
        else:
            answer = "PARTIAL_LIMIT_TEXT" if self.server.scenario == "output-limit" and body.get("tools") else "JADE_VERIFY_DONE"
            if not body.get("tools"):
                answer = "[]"  # Memory 辅助请求返回合法空候选，避免把 JSON 错误当成功证据。
                self.server.aux_requests += 1
                if self.server.memory_delay: time.sleep(.4)
                self.server.aux_finished = time.monotonic()
            if "JADE_VERIFY_LONG" in text and "JADE_VERIFY_TOOL" not in text:
                answer = "\n".join(f"ROW_{i:03} 中文 body" for i in range(120)) + "\nLONG_END"
            content = [{"type": "text", "text": answer}]
        message = {"id": "msg-verify", "type": "message", "role": "assistant", "model": "verify-local",
                   "content": content, "stop_reason": "tool_use" if tool else "max_tokens" if self.server.scenario == "output-limit" and body.get("tools") else "end_turn",
                   "stop_sequence": None, "usage": {"input_tokens": 20, "output_tokens": 10}}
        self.send_response(200)
        if not body.get("stream"):
            encoded = json.dumps(message).encode()
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(encoded)))
            self.end_headers()
            self.wfile.write(encoded)
            return
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Connection", "close")
        self.end_headers()

        def event(kind, **values):
            data = {"type": kind, **values}
            self.wfile.write((f"event: {kind}\ndata: " + json.dumps(data, ensure_ascii=False) + "\n\n").encode())
            self.wfile.flush()

        try:
            event("message_start", message={**message, "content": [], "stop_reason": None})
            block = content[0]
            if tool:
                event("content_block_start", index=0, content_block={**block, "input": {}})
                event("content_block_delta", index=0, delta={"type": "input_json_delta", "partial_json": json.dumps(block["input"])})
            else:
                event("content_block_start", index=0, content_block={"type": "text", "text": ""})
                for offset in range(0, len(block["text"]), 96):
                    event("content_block_delta", index=0, delta={"type": "text_delta", "text": block["text"][offset:offset + 96]})
            event("content_block_stop", index=0)
            event("message_delta", delta={"stop_reason": message["stop_reason"], "stop_sequence": None}, usage={"output_tokens": 10})
            event("message_stop")
        except (BrokenPipeError, ConnectionResetError):
            pass  # 故障场景主动关闭连接，夹具不把它打印为服务端错误。


class Case:
    """一个场景拥有一个临时 HOME/工作区/本地服务/PTY；退出必回收自身进程组。"""

    def __init__(self, root, release, node, name):
        self.directory = root / name
        self.directory.mkdir()
        self.workspace = self.directory / "workspace"
        self.workspace.mkdir()
        self.home = self.directory / "home"
        config = self.home / ".jadecode/config"
        config.mkdir(parents=True)
        (config / "config.json").write_text(json.dumps({"modelMetadata": {"autoDiscover": False}, "tui": {"quietStartup": True}, "mcp": {"servers": {}}}))
        self.target = self.directory / "outside.txt"
        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Endpoint)
        self.server.target = self.target
        self.server.calls = []
        self.server.scenario = name
        self.server.failed_once = False
        self.server.aux_requests = 0
        self.server.aux_finished = 0
        self.server.memory_delay = name == "memory-delay"
        threading.Thread(target=self.server.serve_forever, daemon=True).start()
        self.release = release
        self.env = {"PATH": str(Path(node).parent) + ":/usr/bin:/bin", "HOME": str(self.home),
                    "TERM": "xterm-256color", "LANG": "C.UTF-8", "PS1": "JADE_SHELL> ",
                    "JAVA_TOOL_OPTIONS": "-Duser.home=" + str(self.home),
                    "ANTHROPIC_API_KEY": "dummy-local-verification", "MODEL_ID": "verify-local",
                    "ANTHROPIC_BASE_URL": f"http://127.0.0.1:{self.server.server_port}"}
        self.process = None
        self.master = self.slave = None
        self.output = bytearray()
        self.stages = []
        self.owned_node_pid = None

    def start(self, prompt="", shell=False, mode="fullscreen", extra=None):
        """内核控制 PTY。shell 模式由 bash 真正创建前台作业组，不能用模拟 SIGCONT 替代 fg。"""
        config = self.home / ".jadecode/config/config.json"
        data = json.loads(config.read_text())
        data["tui"]["mode"] = mode
        config.write_text(json.dumps(data))
        self.master, self.slave = pty.openpty()
        self.resize(120, 40)
        self.original = termios.tcgetattr(self.slave)

        def setup():
            os.setsid()
            fcntl.ioctl(0, termios.TIOCSCTTY, 0)

        args = [str(self.release / "jade"), "--permission=workspace"] + ([prompt] if prompt else []) + (extra or [])
        command = ["/bin/bash", "--noprofile", "--norc", "-i"] if shell else args
        self.process = subprocess.Popen(command, cwd=self.workspace, env=self.env,
                                        stdin=self.slave, stdout=self.slave, stderr=self.slave, preexec_fn=setup)
        if shell:
            self.until("JADE_SHELL> ")
            self.send(shlex.join(args) + "\n")
        self.until("Enter submit")
        self.drain(.4)
        assert b"[jade] Node frontend" not in self.output, "startup PID diagnostic leaked to the product terminal"
        self.owned_node_pid = self.node_pid()

    def send(self, value):
        os.write(self.master, value.encode() if isinstance(value, str) else value)

    def drain(self, seconds):
        end = time.monotonic() + seconds
        while time.monotonic() < end:
            if select.select([self.master], [], [], min(.03, max(0, end - time.monotonic())))[0]:
                try:
                    self.output.extend(os.read(self.master, 65536))
                except OSError:
                    break

    def until(self, token, start=0, timeout=12):
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            if token in ANSI.sub(b"", bytes(self.output[start:])).decode("utf8", "replace"):
                return
            if self.process.poll() is not None:
                raise AssertionError(f"process exited {self.process.returncode} before {token}")
            self.drain(.04)
        raise AssertionError(f"timed out waiting for {token}")

    def resize(self, columns, rows):
        fcntl.ioctl(self.slave, termios.TIOCSWINSZ, struct.pack("HHHH", rows, columns, 0, 0))

    def node_pid(self):
        # 只沿本夹具拥有的 Java/bash 子树定位 Node，不依赖向产品终端打印测试 PID。
        pending = [self.process.pid]
        seen = set()
        while pending:
            pid = pending.pop()
            if pid in seen: continue
            seen.add(pid)
            try:
                command = Path(f"/proc/{pid}/cmdline").read_bytes()
                if str(self.release / "lib/frontend/dist/main.js").encode() in command: return pid
                # ProcessBuilder 可能在 Java 虚拟线程的载体线程 fork，子进程不一定列在主线程名下。
                for children in Path(f"/proc/{pid}/task").glob("*/children"):
                    try: pending.extend(int(value) for value in children.read_text().split())
                    except (FileNotFoundError, ProcessLookupError): pass
            except (FileNotFoundError, ProcessLookupError): pass
        raise AssertionError("Node child was not found in the owned process tree")

    def snapshot(self, name, columns=120, rows=40):
        """保存连续字节段；屏幕解析器依次应用各阶段尺寸，检查实际帧而非原始 ANSI 长度。"""
        offset = sum(stage["bytes"] for stage in self.stages)
        chunk = bytes(self.output[offset:])
        (self.directory / (name + ".bin")).write_bytes(chunk)
        self.stages.append({"name": name, "columns": columns, "rows": rows, "bytes": len(chunk)})
        (self.directory / "stages.json").write_text(json.dumps(self.stages))

    def finish(self, expected=0):
        self.send("\x04")
        end = time.monotonic() + 12
        while self.process.poll() is None and time.monotonic() < end:
            self.drain(.04)
        assert self.process.poll() == expected, f"exit {self.process.poll()}, expected {expected}"
        self.drain(.05)
        assert termios.tcgetattr(self.slave) == self.original, "terminal attributes not restored"
        if expected == 0:
            text = ANSI.sub(b"", bytes(self.output)).decode("utf8", "replace")
            assert text.count("Goodbye!") == 1, "farewell must be printed exactly once"
            assert "Resume: jade --resume=" in text, "missing executable resume syntax"

    def close(self):
        # Java 提前退出时 Node 可能尚在收尾；只回收本次启动时记录的子进程。
        # /proc 的命令仍指向本次临时发行包才允许发信号，避免 PID 已复用后伤及其它进程。
        pid = self.owned_node_pid
        try:
            command = Path(f"/proc/{pid}/cmdline").read_bytes() if pid else b""
            if str(self.release).encode() in command:
                group = os.getpgid(pid)
                os.killpg(group, signal.SIGCONT)
                os.killpg(group, signal.SIGTERM)
        except (FileNotFoundError, ProcessLookupError):
            pass
        if self.process and self.process.poll() is None:
            try: os.killpg(self.process.pid, signal.SIGKILL)
            except ProcessLookupError: pass
            self.process.wait(timeout=3)
        if self.master is not None:
            os.close(self.master)
            os.close(self.slave)
        pid_file = self.directory / "mcp.pid"
        if pid_file.exists():
            pid = int(pid_file.read_text())
            try:
                if str(pid_file).encode() in Path(f"/proc/{pid}/cmdline").read_bytes():
                    os.kill(pid, signal.SIGTERM)
            except (FileNotFoundError, ProcessLookupError): pass
        self.server.shutdown()
        self.server.server_close()


def verify(case, name):
    """场景断言直接观察真实副作用/进程/屏幕；每项最多等待明确预算。"""
    if name == "java-only":
        directory = case.directory / "no-node-bin"
        directory.mkdir()
        (directory / "dirname").symlink_to(shutil.which("dirname"))
        env = {**case.env, "PATH": str(directory)}
        frontend = case.release / "lib/frontend"
        hidden = case.release / "lib/frontend-unused"
        frontend.rename(hidden)
        try:
            for option in ("--help", "--version"):
                completed = subprocess.run([str(case.release / "jade"), option], cwd=case.workspace, env=env,
                                           stdin=subprocess.DEVNULL, capture_output=True, timeout=10)
                assert completed.returncode == 0, completed.stderr.decode()
            answer = subprocess.run([str(case.release / "jade"), "--headless", "JADE_VERIFY_TEXT"], cwd=case.workspace,
                                    env=env, stdin=subprocess.DEVNULL, capture_output=True, timeout=20)
            assert answer.returncode == 0 and answer.stdout.decode().strip() == "JADE_VERIFY_DONE"
        finally: hidden.rename(frontend)
        return {"exit": 0, "nodeOnPath": False, "frontendPresent": False, "helpVersionHeadless": True}
    if name in ("headless", "headless-read-only", "headless-full-access", "headless-workspace", "headless-auto-review", "resume", "memory-delay"):
        profile = ["--permission=" + name.removeprefix("headless-")] if name.startswith("headless-") else []
        started = time.monotonic()
        answer = subprocess.run([str(case.release / "jade"), "--headless", *profile,
                                 "JADE_VERIFY_TOOL" if profile else "JADE_VERIFY_TEXT"],
                                cwd=case.workspace, env=case.env, capture_output=True, timeout=20)
        assert answer.returncode == 0, answer.stderr.decode("utf8", "replace")
        assert answer.stdout.decode().strip() == "JADE_VERIFY_DONE", "headless stdout contains non-answer output"
        assert list(case.workspace.rglob("*.jsonl")), "no restorable session was saved"
        if name in ("headless-read-only", "headless-workspace", "headless-auto-review"):
            assert not case.target.exists(), "read-only headless executed a write"
        if name == "headless-full-access":
            assert case.target.read_text() == "APPROVAL_OK", "explicit full-access did not execute the write"
        if name == "memory-delay":
            assert case.server.aux_requests > 0, "Memory did not make an auxiliary request"
            assert case.server.aux_finished <= time.monotonic() and time.monotonic() - started >= .4
            journal = next(case.workspace.rglob("events.jsonl")).read_text()
            assert "MEMORY" in journal, "Memory request usage was not persisted"
        if name == "resume":
            metadata = json.loads(next(case.workspace.rglob("session.json")).read_text())
            before = len(case.server.calls)
            case.start(extra=["--resume=" + metadata["id"]])
            case.until("JADE_VERIFY_DONE")
            case.drain(.2)
            assert len(case.server.calls) == before, "resume re-executed the saved task"
            case.finish()
        return {"exit": 0, "stdout": "final answer only", "httpRequests": len(case.server.calls)}
    if name == "mcp":
        config = case.home / ".jadecode/config/config.json"
        data = json.loads(config.read_text())
        data["mcp"]["servers"]["fixture"] = {"command": sys.executable,
            "args": [str(REPO / "scripts/mcp-fixture.py"), str(case.directory / "mcp.pid")], "readOnlyTools": ["echo"]}
        config.write_text(json.dumps(data))
    prompt = "JADE_VERIFY_MCP" if name == "mcp" else "JADE_VERIFY_TEAM" if name == "team" else "JADE_VERIFY_TOOL" if name.startswith("approval") else "JADE_VERIFY_LONG" if name == "long-output" else ""
    case.start("JADE_VERIFY_LONG" if name == "regular-approval" else "JADE_VERIFY_TEXT" if name in ("copy", "retry", "model-error", "output-limit") else prompt,
               shell=name == "approval-suspend", mode="regular" if name in ("regular", "regular-approval") else "fullscreen")
    if name == "input-routing":
        def screen(label):
            case.snapshot(label)
            parsed = subprocess.run([shutil.which("node"), str(REPO / "scripts/tui-screen.mjs"), str(case.directory)],
                                    capture_output=True, text=True, check=True)
            return json.loads(parsed.stdout)[-1]["text"]
        case.send("DRAFT_CLEAR_NOW"); case.drain(.15)
        assert "DRAFT_CLEAR_NOW" in screen("draft")
        case.send("\x03"); case.drain(.15)
        assert "DRAFT_CLEAR_NOW" not in screen("cleared"), "Ctrl+C needed another key to repaint"
        case.send("/settings\r"); case.until("Settings"); case.drain(.15)
        case.send("\x1b[1;1B\x1b[1;1:3B"); case.drain(.15)
        assert "▸ Light theme" in screen("one-step"), "one Down press/release moved twice"
        for ch in "wheel": case.send(f"\x1b[{ord(ch)}u\x1b[{ord(ch)};1:3u")
        case.drain(.15)
        assert "Wheel scroll lines" in screen("filter"), "printable release duplicated the filter"
        case.send("\x1b"); case.drain(.15)
        # /resume 在没有其它会话时会直接提示后返回；先准备真实候选，再验证其过滤输入。
        case.send("/name ROUTING_ORIGINAL\r"); case.until("ROUTING_ORIGINAL"); case.drain(.15)
        case.send("/new\r")
        deadline = time.monotonic() + 5
        while len(list(case.workspace.rglob("session.json"))) < 2 and time.monotonic() < deadline: case.drain(.04)
        assert len(list(case.workspace.rglob("session.json"))) == 2, "could not prepare a resume candidate"
        case.drain(.3)
        resume_start = len(case.output)
        case.send("/resume\r"); case.until("ROUTING_ORIGINAL", resume_start); case.drain(.15)
        case.send("\x1b[120u\x1b[120;1:3u"); case.drain(.15)
        current = screen("resume-filter")
        assert "Filter: x" in current and "Filter: xx" not in current, "resume duplicated the printable release"
        case.send("\x1b"); case.drain(.15)
        case.send("first\x1b[13;2usecond\nthird"); case.drain(.15)
        assert not case.server.calls, "newline submitted the draft"
        case.send("\x03"); case.drain(.15)
        case.send("DRAFT_SEARCH_KEPT"); case.drain(.1)
        case.send("\x12"); case.until("Find in transcript"); case.drain(.15)
        current = screen("search-overlay")
        lines = current.splitlines()
        assert lines[2].index("Find in transcript") > 60, "search is not a top-right overlay"
        assert "DRAFT_SEARCH_KEPT" in current, "search replaced the editor dock"
        case.send("\x1b"); case.drain(.1)
        case.send("\x03"); case.drain(.1)
        case.finish()
        return {"exit": 0, "draftRepaint": True, "pageReleaseFiltered": True, "newlinePreserved": True, "searchKey": "ctrl+r"}
    if name == "commands":
        # 所有命令均经过实际编辑器/slash 路由；页面不能误调用模型。
        for command, label in [("/settings", "Settings"), ("/session", "Generation:"),
                               ("/hotkeys", "Keys and commands"), ("/model", "Context window:"),
                               ("/mcp", "MCP"), ("/permission", "auto-review (Approve for Me)")]:
            before = len(case.output)
            case.send(command + "\r")
            case.until(label, before)
            case.send("\x1b"); case.drain(.12)
        case.send("/name COMMAND_TITLE\r"); case.until("COMMAND_TITLE"); case.drain(.15)
        case.send("/thinking\r"); case.until("Thinking:"); case.drain(.15)
        case.send("/copy\r"); case.until("No assistant reply to copy"); case.drain(.15)
        old_id = json.loads(next(case.workspace.rglob("session.json")).read_text())["id"]
        case.send("/new\r")
        deadline = time.monotonic() + 5
        while len(list(case.workspace.rglob("session.json"))) < 2 and time.monotonic() < deadline: case.drain(.04)
        assert len(list(case.workspace.rglob("session.json"))) == 2, "/new did not create a session"
        case.drain(.3)
        before = len(case.output)
        case.send("/resume " + old_id + "\r"); case.until("COMMAND_TITLE", before)
        case.drain(.2)
        assert not case.server.calls, "local commands unexpectedly called the model"
        case.send("/quit\r")
        # 退出回放也写 PTY；等待时必须继续读取，否则测试自己会堵住终端输出缓冲。
        deadline = time.monotonic() + 12
        while case.process.poll() is None and time.monotonic() < deadline: case.drain(.04)
        case.drain(.1)
        assert case.process.returncode == 0
        assert termios.tcgetattr(case.slave) == case.original
        assert ANSI.sub(b"", bytes(case.output)).count(b"Goodbye!") == 1
        return {"exit": 0, "commands": 12, "httpRequests": 0, "renamedAndResumed": True}
    if name in ("retry", "model-error", "output-limit"):
        case.until("JADE_VERIFY_DONE" if name == "retry" else "LOCAL_MODEL_FAILURE" if name == "model-error" else "Incomplete reply")
        if name == "output-limit":
            # 截断正文可以保留为预览；同时等待真实任务失败，不能只凭界面标记就认定正确收尾。
            assert b"PARTIAL_LIMIT_TEXT" in case.output
            case.until("Model output reached max_tokens")
            assert len([call for call in case.server.calls if call.get("tools")]) == 1, "truncation silently retried the model"
            assert case.server.aux_requests == 0, "truncated turn incorrectly reached Memory maintenance"
        if name == "retry": assert len([call for call in case.server.calls if call.get("tools")]) == 2
        case.finish()
        return {"exit": 0, "modelBoundary": name, "httpRequests": len(case.server.calls)}
    if name == "copy":
        case.until("JADE_VERIFY_DONE")
        case.drain(.2)
        case.send("/copy\r")
        case.until("Copied!")
        assert b"\x1b]52;c;" in case.output, "headless desktop environment must use OSC52 fallback"
        case.drain(1.2)
        before = len(case.output)
        case.finish()
        assert "Copied!" not in ANSI.sub(b"", bytes(case.output[before:])).decode(), "copy feedback leaked into exit transcript"
        return {"exit": 0, "osc52": "sent", "feedback": "transient"}
    if name == "regular-approval":
        case.until("LONG_END"); case.drain(.2)
        case.send("JADE_VERIFY_TOOL\r")
    if name in ("mcp", "team"):
        case.until("JADE_VERIFY_DONE")
        results = [block for call in case.server.calls for msg in call.get("messages", [])
                   for block in msg.get("content", []) if isinstance(block, dict) and block.get("type") == "tool_result"]
        expected = "MCP_ECHO_OK" if name == "mcp" else "agent_"
        assert any(expected in json.dumps(block) for block in results), "domain result never reached the next SDK request"
        if name == "mcp":
            case.send("/mcp\r")
            case.until("fixture · connected")
            case.send("\x1b"); case.drain(.1)
        case.finish()
        if name == "mcp":
            pid = int((case.directory / "mcp.pid").read_text())
            assert not Path(f"/proc/{pid}").exists(), "MCP child survived normal session shutdown"
        return {"exit": 0, "localDomainFlow": name, "httpRequests": len(case.server.calls)}
    if name == "full-access":
        case.send("/permission\r")
        case.until("Permission")
        case.send("\x1b[B\x1b[B\r")
        case.until("Full Access requires")
        case.drain(.2)
        case.send("\x1b[B")
        case.drain(.1)
        case.send("\r")
        case.until("perm full-access")
        # 权限真正在 Java 生效后，再提交越出工作区但仍属于本夹具的写任务。
        case.send("JADE_VERIFY_TOOL\r")
        case.until("JADE_VERIFY_DONE")
        assert case.target.read_text() == "APPROVAL_OK"
        case.finish()
        return {"exit": 0, "fullAccessGate": "confirmed", "approvedWrite": True}
    if name in ("sighup", "sigint", "sigterm", "java-exit"):
        sig = signal.SIGHUP if name == "sighup" else signal.SIGINT if name == "sigint" else signal.SIGTERM
        target = case.node_pid() if name != "java-exit" else case.process.pid
        os.kill(target, sig if name != "java-exit" else signal.SIGKILL)
        end = time.monotonic() + 12
        while case.process.poll() is None and time.monotonic() < end: case.drain(.04)
        assert case.process.poll() is not None, "peer remained alive after process failure"
        # Java 已退出不等于 Node 已收尾；继续读取它继承的 PTY，等待终端恢复。
        deadline = time.monotonic() + 3
        while b"\x1b[?1049l" not in case.output and time.monotonic() < deadline: case.drain(.05)
        expected = 129 if name == "sighup" else 130 if name == "sigint" else 143 if name == "sigterm" else -9
        assert case.process.returncode == expected, f"signal exit {case.process.returncode}, expected {expected}"
        assert b"\x1b[?1049l" in case.output, "Node did not restore alternate screen"
        assert termios.tcgetattr(case.slave) == case.original, "terminal attributes not restored"
        return {"exit": expected, "terminalRestored": True, "signalTarget": "single pid"}
    if name.startswith("approval") or name == "regular-approval":
        case.until("Allow once")
        case.drain(.3)
        assert not case.target.exists(), "tool ran without confirmation"
        if name == "approval-small":
            case.resize(40, 8); case.drain(.3)
            case.send("\x1b[B\r"); case.drain(.15)
            assert not case.target.exists(), "a clipped approval accepted a key"
            case.resize(120, 40); case.drain(.3)
        if name == "approval-resize":
            case.resize(80, 24)
            case.drain(.4)
            case.send("\x1b[13;1:2u")  # repeat 不能复用旧审批证据。
            case.drain(.1)
            assert not case.target.exists()
        if name == "approval-suspend":
            before = len(case.output)
            case.send("\x1a")
            case.until("Stopped", before)
            case.send("fg\n")
            case.drain(.7)
            # 驱动不在子进程的 session 中，不能对其控制 PTY 调用 tcgetpgrp；从内核进程资料核对前台组。
            fields = Path(f"/proc/{case.node_pid()}/stat").read_text().rsplit(") ", 1)[1].split()
            assert fields[0] != "T" and fields[2] == fields[5], "fg did not return the job to the foreground"
        if name == "approval-mouse":
            case.snapshot("button")
            parsed = subprocess.run([shutil.which("node"), str(REPO / "scripts/tui-screen.mjs"), str(case.directory)],
                                    capture_output=True, text=True, check=True)
            lines = json.loads(parsed.stdout)[-1]["text"].splitlines()
            y = next(index for index, line in enumerate(lines) if "Allow once" in line)
            x = lines[y].index("Allow once")
            case.send(f"\x1b[<2;{x+1};{y+1}M\x1b[<2;{x+1};{y+1}m")
            case.send(f"\x1b[<32;{x+1};{y+1}M")
            case.drain(.15)
            assert not case.target.exists(), "right-click or drag approved an action"
            case.send(f"\x1b[<0;{x+1};{y+1}M")
            case.drain(.2)
            assert not case.target.exists(), "the first left click approved instead of only selecting"
            case.send(f"\x1b[<0;{x+1};{y+1}m")
            case.send(f"\x1b[<0;{x+1};{y+1}M")
        else:
            case.send("\x1b" if name == "approval-deny" else "\x1b[B\r")
        case.until("JADE_VERIFY_DONE")
        if name == "approval-deny":
            assert not case.target.exists(), "Deny executed the write"
            case.finish()
            return {"exit": 0, "deniedWrite": True}
        assert case.target.read_text() == "APPROVAL_OK", "approval did not execute exactly the requested write"
        task_calls = [call for call in case.server.calls if call.get("tools")]
        assert len(task_calls) == (3 if name == "regular-approval" else 2), "task was retried/resubmitted unexpectedly"
        if name == "approval-suspend":
            case.send("\x04")
            case.until("JADE_SHELL> ", start=len(case.output) - 1)
            case.send("exit\n")
            case.process.wait(timeout=5)
        else:
            case.finish()
        return {"approvedWrite": True, "formalRequests": len(task_calls), "httpRequests": len(case.server.calls), "exit": case.process.returncode}
    if name == "long-output":
        case.until("LONG_END")
        case.drain(.2)
        case.snapshot("tail")
        case.send("\x1b[1;5H")
        case.drain(.2)
        case.snapshot("history")
        case.send("\x1b[1;5F")
        case.drain(.2)
        case.snapshot("end")
        parsed = subprocess.run([shutil.which("node"), str(REPO / "scripts/tui-screen.mjs"), str(case.directory)],
                                capture_output=True, text=True, check=True)
        screens = json.loads(parsed.stdout)
        assert "ROW_000" in screens[1]["text"], "history did not scroll to the first row"
        assert "LONG_END" in screens[2]["text"], "scroll-to-bottom lost the final output"
        # 搜索不能只改变浮层计数；无需Enter，导航中的正文匹配就应进入真实PTY视口。
        case.send("\x12"); case.until("Find in transcript"); case.drain(.1)
        case.send("ROW_"); case.drain(.15)
        case.send("\x1b[B" * 80); case.drain(.2)
        case.snapshot("search-down")
        case.send("\x1b[A" * 60); case.drain(.2)
        case.snapshot("search-up")
        parsed = subprocess.run([shutil.which("node"), str(REPO / "scripts/tui-screen.mjs"), str(case.directory)],
                                capture_output=True, text=True, check=True)
        screens = json.loads(parsed.stdout)
        assert "ROW_080" in screens[-2]["text"], "Down changed the selection but did not reveal its transcript row"
        assert "ROW_020" in screens[-1]["text"], "Up changed the selection but did not reveal its transcript row"
        case.send("\x1b"); case.drain(.1)
    elif name == "node-exit":
        os.kill(case.node_pid(), signal.SIGKILL)
        case.process.wait(timeout=12)
        assert case.process.returncode != 0
        return {"exit": case.process.returncode, "forcedNodeDeath": True}
    else:
        case.send("/settings\r")
        case.until("Settings")
        case.send("\x1b")
        case.drain(.15)
    case.finish()
    return {"exit": 0, "httpRequests": len(case.server.calls)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--release", type=Path, default=REPO / "dist/jadecode")
    parser.add_argument("--scenario", action="append", choices=["java-only", "smoke", "input-routing", "commands", "regular", "regular-approval", "copy", "retry", "model-error", "output-limit", "headless", "headless-read-only", "headless-full-access", "headless-workspace", "headless-auto-review", "memory-delay", "mcp", "team", "resume", "long-output", "approval-resize", "approval-small", "approval-mouse", "approval-suspend", "approval-deny", "full-access", "sighup", "sigint", "sigterm", "java-exit", "node-exit"])
    parser.add_argument("--report", type=Path, default=REPO / "target/tui-release-verification.json")
    options = parser.parse_args()
    release = options.release.resolve()
    frontend = release / "lib/frontend"
    manifest = json.loads((frontend / "frontend-manifest.json").read_text())
    assert manifest.get("files"), "release manifest does not contain compiled-file hashes"
    for item in manifest["files"]:
        assert hashlib.sha256((frontend / item["path"]).read_bytes()).hexdigest() == item["sha256"], f"release file drift: {item['path']}"
    for item in manifest["assets"]:
        assert hashlib.sha256((frontend / "assets" / item["path"]).read_bytes()).hexdigest() == item["sha256"], "asset hash drift"
    for item in manifest["native"]:
        assert hashlib.sha256((frontend / item["path"]).read_bytes()).hexdigest() == item["sha256"], "native hash drift"
    assert len(manifest["licenses"]) == 5, "missing production licenses in manifest"
    for relative in manifest["licenses"]:
        assert (frontend / relative).is_file(), "missing frontend license: " + relative
    assert len(list((release / "licenses").glob("*.txt"))) == 5, "incomplete release licenses"
    with zipfile.ZipFile(release / "lib/jadecode.jar") as jar:
        assert manifest["buildId"] in jar.read("META-INF/jade-build.properties").decode(), "unpaired release"
    report = {"buildId": manifest["buildId"], "jarSha256": hashlib.sha256((release / "lib/jadecode.jar").read_bytes()).hexdigest(), "cases": []}
    with tempfile.TemporaryDirectory(prefix="jade-release-verify-") as directory:
        root = Path(directory)
        shutil.copytree(release, root / "release", symlinks=True)
        for name in options.scenario or ["java-only", "smoke", "input-routing", "commands", "regular", "regular-approval", "copy", "retry", "model-error", "output-limit", "headless", "headless-read-only", "headless-full-access", "headless-workspace", "headless-auto-review", "memory-delay", "mcp", "team", "resume", "long-output", "approval-resize", "approval-small", "approval-mouse", "approval-suspend", "approval-deny", "full-access", "sighup", "sigint", "sigterm", "java-exit", "node-exit"]:
            case = Case(root, root / "release", shutil.which("node"), name)
            try:
                result = {"name": name, "status": "pass", **verify(case, name)}
            except Exception as failure:
                result = {"name": name, "status": "fail", "error": str(failure)}
                # 只保留隔离夹具产生的尾部文字，帮助下一次重放根因。
                result["terminalTail"] = ANSI.sub(b"", bytes(case.output[-4000:])).decode("utf8", "replace")
            finally:
                case.close()
            report["cases"].append(result)
            print(json.dumps(result, ensure_ascii=False), flush=True)
    options.report.parent.mkdir(parents=True, exist_ok=True)
    options.report.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    return 1 if any(case["status"] == "fail" for case in report["cases"]) else 0


if __name__ == "__main__":
    raise SystemExit(main())
