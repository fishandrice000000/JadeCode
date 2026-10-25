#!/usr/bin/env python3
"""验证实际 ClipboardController 的 OSC52 经过私有 tmux 的行为，不接触用户服务器或桌面剪贴板。"""
import base64
import fcntl
import json
import os
from pathlib import Path
import pty
import select
import shutil
import signal
import struct
import subprocess
import tempfile
import termios
import time

ROOT = Path(__file__).resolve().parent.parent


def main():
    node = shutil.which("node")
    tmux = shutil.which("tmux")
    if not node or not tmux:
        raise SystemExit("This isolated verification requires node and tmux")
    with tempfile.TemporaryDirectory(prefix="jade-tmux-clipboard-") as directory:
        work = Path(directory)
        socket = str(work / "tmux.sock")
        env = {"HOME": directory, "PATH": str(Path(node).parent) + ":/usr/bin:/bin", "TERM": "xterm-256color", "LANG": "C.UTF-8"}
        module = ROOT / "dist/jadecode/lib/frontend/dist/services/clipboard-controller.js"
        fixture = work / "fixture.mjs"
        fixture.write_text("import{existsSync,writeFileSync}from'node:fs';\n"
            + "import{ClipboardController}from " + json.dumps(module.as_uri()) + ";\n"
            + "for(const [phase,text]of [['blocked','BLOCKED'],['allowed','TMUX_CLIPBOARD_OK']]){\n"
            + "while(!existsSync(" + json.dumps(directory + "/") + "+phase))await new Promise(r=>setTimeout(r,10));\n"
            + "const result=await new ClipboardController().copy(text);writeFileSync(" + json.dumps(directory + "/")
            + "+phase+'.json',JSON.stringify(result));}\nsetInterval(()=>{},1000);\n")
        def run(*args):
            # 每条命令固定绑定本次 socket，绝不能省略 -S 而操作用户 tmux。
            return subprocess.run([tmux, "-S", socket, *args], env=env, capture_output=True, text=True, timeout=5, check=True)
        master, slave = pty.openpty()
        fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack("HHHH", 24, 80, 0, 0))
        output = bytearray()
        client = None
        def drain(seconds):
            end = time.monotonic() + seconds
            while time.monotonic() < end:
                if select.select([master], [], [], .02)[0]:
                    try: output.extend(os.read(master, 65536))
                    except OSError: break
        def wait(path):
            end = time.monotonic() + 5
            while not path.exists() and time.monotonic() < end: drain(.03)
            assert path.exists(), "clipboard fixture did not complete"
            drain(.15)
        try:
            run("-f", "/dev/null", "new-session", "-d", "-s", "verify", node, str(fixture))
            run("set-option", "-s", "set-clipboard", "external")
            run("set-option", "-as", "terminal-features", ",xterm-256color:clipboard")
            client = subprocess.Popen([tmux, "-S", socket, "attach-session", "-t", "verify"],
                env=env, stdin=slave, stdout=slave, stderr=slave, start_new_session=True)
            drain(.4)
            (work / "blocked").touch(); wait(work / "blocked.json")
            assert not run("list-buffers").stdout.strip(), "external policy accepted an application's OSC52"
            before = len(output)
            run("set-option", "-s", "set-clipboard", "on")
            (work / "allowed").touch(); wait(work / "allowed.json")
            assert run("show-buffer").stdout == "TMUX_CLIPBOARD_OK"
            assert base64.b64encode(b"TMUX_CLIPBOARD_OK") in output[before:], "OSC52 was not forwarded to the fake outer terminal"
            assert json.loads((work / "allowed.json").read_text())["status"] == "OSC52_SENT"
            report = {"tmux": subprocess.check_output([tmux, "-V"], text=True).strip(),
                "externalBlocksApplication": True, "onCreatesBufferAndForwardsOsc52": True,
                "desktopClipboardTouched": False, "exit": 0}
            target = ROOT / "target/tmux-clipboard-verification.json"
            target.parent.mkdir(exist_ok=True); target.write_text(json.dumps(report, indent=2) + "\n")
            print(json.dumps(report))
        finally:
            subprocess.run([tmux, "-S", socket, "kill-server"], env=env, capture_output=True, timeout=5)
            if client:
                try: client.wait(timeout=3)
                except subprocess.TimeoutExpired: os.kill(client.pid, signal.SIGKILL); client.wait(timeout=3)
            os.close(master); os.close(slave)


if __name__ == "__main__":
    main()
