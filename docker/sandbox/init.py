"""容器 PID 1：只等待信号并回收孤儿进程，不监听网络、不读取宿主配置。"""
import os
import signal
import sys


def reap(_signum, _frame):
    # waitpid 的 WNOHANG 保证没有可回收子进程时立即返回，避免占用 CPU。
    try:
        while os.waitpid(-1, os.WNOHANG)[0]:
            pass
    except ChildProcessError:
        pass


signal.signal(signal.SIGCHLD, reap)
signal.signal(signal.SIGTERM, lambda _s, _f: sys.exit(0))
while True:
    signal.pause()
