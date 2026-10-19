"""Windows control panel for device-side sonar status, board and controls."""
from __future__ import annotations

import argparse
from collections import deque
import json
import math
import os
from pathlib import Path
import queue
import re
import subprocess
import threading
import time
import tkinter as tk
from tkinter import ttk, messagebox

PROJECT = Path(__file__).resolve().parent
LOCAL_CONFIG = PROJECT / "local.json"
LOCAL = json.loads(LOCAL_CONFIG.read_text(encoding="utf-8")) if LOCAL_CONFIG.exists() else {}
ADB = Path(LOCAL["adb"]) if LOCAL else None
REMOTE = "/data/local/tmp/sonar-independent"
POWERSHELL = str(Path(os.environ["SystemRoot"]) / "System32/WindowsPowerShell/v1.0/powershell.exe")
BG, PANEL, TEXT, MUTED = "#111b28", "#1a293b", "#e6edf5", "#9aadc2"
CELL_COLORS = {-1: "#34465b", 0: "#566577", 1: "#8796a5", 2: "#d39a54"}
OPEN_COLOR, TARGET_COLOR, CONFIRMED_COLOR = "#4f92ba", "#ffda77", "#75dba8"
TARGETS = {"International": "真实国际服"}


def run_hidden(argv):
    return subprocess.run(argv, cwd=PROJECT, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                          creationflags=subprocess.CREATE_NO_WINDOW, timeout=120)


def read_device(*args, target="International"):
    # This bundled ADB's shell adds CR before LF. Preserve file bytes so the
    # next remote tail offset still points to the start of a JSONL record.
    serial = LOCAL.get("serial", "emulator-5554")
    result = subprocess.run([str(ADB), "-s", serial, "exec-out", *args],
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                            creationflags=subprocess.CREATE_NO_WINDOW, timeout=15)
    if result.returncode:
        raise OSError(result.stderr.decode("utf-8", errors="replace").strip())
    return result.stdout


def scout_label(active, preferred):
    """Describe the effective mode without changing the user's preference."""
    active = active or {}
    preferred = active.get("scoutMode", preferred)
    effective = active.get("effectiveScoutMode", preferred)
    if preferred == "Red" and effective == "Blue":
        return "红侦未解锁，暂用蓝侦"
    return "蓝弹离线侦察" if effective == "Blue" else "红弹离线侦察"


class ViewSnapshot:
    """Display facts already recorded by the independent device runner."""
    def __init__(self):
        self.board_event = None
        self.phase = "等待阶段记录"
        self.level_index = 1
        self.level_started_ms = None
        self.level_finished_ms = None
        self.last_elapsed_ms = 0
        self.run_started_mono = None
        self.actual_target = self.prepared_target = None
        self.confirmed_batch = set()
        self.logs = deque(maxlen=160)

    def apply(self, entry, device_epoch_origin=None):
        name = entry.get("event")
        elapsed = entry.get("elapsedMs", 0)
        self.last_elapsed_ms = elapsed
        if self.run_started_mono is None and device_epoch_origin is not None and "time" in entry:
            # Map the device's recorded run start onto the local monotonic clock.
            self.run_started_mono = (entry["time"]-elapsed)/1000-device_epoch_origin
        self.phase = entry.get("phase", self.phase)
        if name == "started":
            self.level_started_ms = 0
        if name in ("initial", "next_level"):
            self.level_index = entry.get("levels", 0) + 1
            self.level_started_ms = 0 if name == "initial" else elapsed
            self.level_finished_ms = None
            self.actual_target = self.prepared_target = None
            self.confirmed_batch.clear()
        if name in ("victory", "finished") and self.level_finished_ms is None:
            self.level_finished_ms = elapsed
        if name in ("attempt_red", "attempt_blue", "attempt_blue_scout"):
            match = re.search(r"\((\d+),(\d+)\)", self.phase)
            self.prepared_target = tuple(map(int, match.groups())) if match else None
        if name in ("scout_observed", "blue_observed", "blue_miss"):
            self.actual_target, self.prepared_target = self.prepared_target, None
            if name in ("blue_observed", "blue_miss") and self.actual_target:
                self.confirmed_batch = {"%d,%d" % self.actual_target}
        if entry.get("board"):
            rows = entry["board"].splitlines()
            n, h = entry["size"], entry.get("grid")
            centers = None
            if h and len(h) == 8:
                centers = [[((h[0]*r+h[1]*c+h[2])/(h[6]*r+h[7]*c+1),
                             (h[3]*r+h[4]*c+h[5])/(h[6]*r+h[7]*c+1))
                            for c in range(n)] for r in range(n)]
            self.board_event = {"n": n, "centers": centers,
                                # Display values: -1 unknown, 1 observed
                                # water, 2 ship; 0 is inferred water, not unknown.
                                "board": [[{"?": -1, "~": 1, "+": 2, "#": 2}[c] for c in row] for row in rows],
                                "opened": [[c == "#" for c in row] for row in rows]}
        labels = {
            "started": "开始新一轮，重新识别当前关卡",
            "initial": f"读取棋盘 {entry.get('size')}×{entry.get('size')} · 画面弹量读数：红 {entry.get('red')} / 蓝 {entry.get('blue')}",
            "attempt_red": self.phase, "attempt_blue": self.phase, "attempt_blue_scout": self.phase,
            "scout_observed": "已读取离线侦察画面",
            "red_reconciled": f"通信已恢复 · 红弹已恢复至 {entry.get('red')}",
            "blue_scout_reconciled": "通信已恢复 · 蓝弹侦察流程结束",
            "mode_changed": self.phase,
            "deduced": "求解器确认新的船位",
            "blue_observed": f"目标格攻击结果已确认 · 本次蓝弹尝试 {entry.get('blueActions')}",
            "blue_miss": "目标格显示水格，停止核对",
            "victory": "已观察到胜利画面",
            "next_level": f"进入下一关 · {entry.get('size')}×{entry.get('size')}",
            "finished": self.phase,
        }
        if name in labels:
            self.logs.append(f"{elapsed/1000:6.1f}s  {labels[name]}")

    def level_seconds(self, live=False, now=None):
        if self.level_started_ms is None:
            return None
        end = self.level_finished_ms if self.level_finished_ms is not None else self.last_elapsed_ms
        if live and self.level_finished_ms is None and self.run_started_mono is not None:
            current = time.monotonic() if now is None else now
            end = max(end, (current-self.run_started_mono)*1000)
        return max(0, end-self.level_started_ms)/1000


def board_polygons(snapshot, width, height, padding=32):
    """Use the recorded screen coordinates, preserving their orientation."""
    if not snapshot:
        return [], "尚无棋盘快照"
    n, centers, board = snapshot.get("n"), snapshot.get("centers"), snapshot.get("board")
    if not isinstance(n, int) or n < 2 or not isinstance(centers, list) or len(centers) != n:
        return [], "此快照未记录格心坐标，无法绘制真实方向"
    if not isinstance(board, list) or len(board) != n:
        return [], "棋盘快照不完整"
    try:
        for row in centers:
            if len(row) != n:
                raise ValueError()
            for point in row:
                if len(point) != 2 or not all(math.isfinite(float(v)) for v in point):
                    raise ValueError()
        polygons = []
        for r in range(n):
            if len(board[r]) != n:
                raise ValueError()
            for c in range(n):
                x, y = centers[r][c]
                ra, rb, ca, cb = max(0,r-1), min(n-1,r+1), max(0,c-1), min(n-1,c+1)
                dr = [(centers[rb][c][i] - centers[ra][c][i])/(rb-ra) for i in (0,1)]
                dc = [(centers[r][cb][i] - centers[r][ca][i])/(cb-ca) for i in (0,1)]
                corners = [(x + sr*dr[0]/2 + sc*dc[0]/2,
                            y + sr*dr[1]/2 + sc*dc[1]/2)
                           for sr,sc in ((-1,-1),(-1,1),(1,1),(1,-1))]
                polygons.append({"r": r+1, "c": c+1, "center": (x,y), "corners": corners,
                                 "value": board[r][c]})
        all_points = [p for cell in polygons for p in cell["corners"]]
        xmin, xmax = min(p[0] for p in all_points), max(p[0] for p in all_points)
        ymin, ymax = min(p[1] for p in all_points), max(p[1] for p in all_points)
        if xmax <= xmin or ymax <= ymin:
            raise ValueError()
        scale = min(max(1,width-2*padding)/(xmax-xmin), max(1,height-2*padding)/(ymax-ymin))
        ox, oy = (width-(xmax-xmin)*scale)/2, (height-(ymax-ymin)*scale)/2
        transform = lambda p: (ox+(p[0]-xmin)*scale, oy+(p[1]-ymin)*scale)
        for cell in polygons:
            cell["center"] = transform(cell["center"])
            cell["corners"] = [transform(p) for p in cell["corners"]]
        return polygons, ""
    except (TypeError, ValueError, IndexError, ZeroDivisionError):
        return [], "格心坐标不完整，未绘制推测棋盘"


class RuntimeWindow:
    def __init__(self, root, options, replay_path=None):
        self.root, self.options, self.replay_path = root, options, replay_path
        self.messages = queue.Queue()
        self.monitor_stop = threading.Event()
        self.model = ViewSnapshot()
        self.active = {}
        self.launch_pending = self.command_pending = False
        self.mode_pending = False
        self.mode_requested = None
        self.stop_requested = self.stop_command_pending = self.close_requested = False
        self.stop_sent = False
        self.before_start = None
        self.notice = ""
        self._last_logs = None
        root.title("V2 声纳 · 历史回放" if replay_path else "V2 声纳运行窗口")
        root.geometry("1080x700")
        root.minsize(860, 600)
        root.configure(bg=BG)
        root.protocol("WM_DELETE_WINDOW", lambda: self.on_stop(close_after=True))
        self._build()
        self.menu = tk.Menu(root, tearoff=False)
        self.menu.add_command(label="运行设置…", command=self.settings)
        root.bind("<Button-3>", lambda e: self.menu.tk_popup(e.x_root, e.y_root))
        threading.Thread(target=self._monitor, daemon=True).start()
        root.after(80, self._poll)

    def _build(self):
        style = ttk.Style(self.root)
        style.theme_use("clam")
        style.configure("Run.TButton", font=("Microsoft YaHei UI",11), padding=(18,8))
        top = tk.Frame(self.root, bg=BG)
        top.pack(fill="x", padx=24, pady=(20,10))
        tk.Label(top, text="声纳运行", font=("Microsoft YaHei UI",22,"bold"), bg=BG, fg=TEXT).pack(side="left")
        self.stop_button = ttk.Button(top, text="停止", style="Run.TButton", command=self.on_stop)
        self.stop_button.pack(side="right", padx=(10,0))
        self.start_button = ttk.Button(top, text="开始", style="Run.TButton", command=self.on_start)
        self.start_button.pack(side="right")
        tk.Label(top, text="真实国际服", bg=BG, fg=MUTED,
                 font=("Microsoft YaHei UI",11)).pack(side="right", padx=10)
        self.blue_scout = tk.BooleanVar(value=self.options.get("scout", "Red") == "Blue")
        self.mode_button = tk.Checkbutton(top, text="蓝弹离线侦察", variable=self.blue_scout, command=self.on_mode,
                                          bg=BG, fg=TEXT, selectcolor=PANEL, activebackground=BG,
                                          activeforeground=TEXT, font=("Microsoft YaHei UI",11))
        self.mode_button.pack(side="right", padx=18)
        self.status = tk.StringVar(value="正在读取运行记录")
        tk.Label(self.root, textvariable=self.status, anchor="w", bg=BG, fg=MUTED,
                 font=("Microsoft YaHei UI",10)).pack(fill="x", padx=24)
        info = tk.Frame(self.root, bg=PANEL)
        info.pack(fill="x", padx=24, pady=(14,12))
        self.level, self.phase, self.duration, self.target = [tk.StringVar() for _ in range(4)]
        for title, variable, weight in (("关卡",self.level,1),("阶段",self.phase,2),
                                        ("本关耗时",self.duration,1),("目标",self.target,3)):
            col = info.grid_size()[0]
            pane = tk.Frame(info,bg=PANEL)
            pane.grid(row=0,column=col,sticky="nsew",padx=14,pady=12)
            info.columnconfigure(col,weight=weight)
            tk.Label(pane,text=title,bg=PANEL,fg=MUTED,font=("Microsoft YaHei UI",9)).pack(anchor="w")
            tk.Label(pane,textvariable=variable,bg=PANEL,fg=TEXT,font=("Microsoft YaHei UI",11),
                     wraplength=270,justify="left").pack(anchor="w",pady=(5,0))
        body = tk.Frame(self.root,bg=BG)
        body.pack(fill="both",expand=True,padx=24,pady=(0,12))
        body.columnconfigure(0,weight=3)
        body.columnconfigure(1,weight=2)
        body.rowconfigure(0,weight=1)
        left = tk.Frame(body,bg=PANEL)
        left.grid(row=0,column=0,sticky="nsew",padx=(0,12))
        self.canvas = tk.Canvas(left,bg=PANEL,highlightthickness=0)
        self.canvas.pack(fill="both",expand=True)
        self.canvas.bind("<Configure>", lambda _event: self._draw_board())
        tk.Label(left,text="灰：未知 / 空格   橙：船体信息   蓝：画面已开（非提交证明）\n"
                           "黄框：最近结果目标   绿框：最近已观察到的攻击结果",
                 bg=PANEL,fg=MUTED,font=("Microsoft YaHei UI",9),justify="left").pack(anchor="w",padx=14,pady=(0,12))
        right = tk.Frame(body,bg=PANEL)
        right.grid(row=0,column=1,sticky="nsew")
        tk.Label(right,text="最近日志",bg=PANEL,fg=TEXT,font=("Microsoft YaHei UI",12,"bold")).pack(anchor="w",padx=14,pady=12)
        self.log_box = tk.Text(right,bg=PANEL,fg=TEXT,relief="flat",wrap="word",state="disabled",
                               font=("Microsoft YaHei UI",9),width=35,padx=14,pady=4)
        self.log_box.pack(fill="both",expand=True,pady=(0,12))
        self._render()

    def _monitor(self):
        offset, last_error = 0, None
        device_epoch_origin = None
        monitored_target = None
        while not self.monitor_stop.is_set():
            target = self.options["target"]
            if target != monitored_target:
                offset, last_error, device_epoch_origin = 0, None, None
                monitored_target = target
            active = None
            try:
                if self.replay_path:
                    raw = self.replay_path.read_bytes()[offset:]
                    active = None
                else:
                    if device_epoch_origin is None:
                        before = time.monotonic()
                        device_epoch = float(read_device("date", "+%s%3N", target=target))/1000
                        device_epoch_origin = device_epoch-(before+time.monotonic())/2
                    active = json.loads(read_device("cat", REMOTE+"/status.json", target=target))
                    raw = read_device("tail", "-c", f"+{offset+1}", REMOTE+"/events.jsonl", target=target)
                complete = raw.rfind(b"\n") + 1
                events = [json.loads(line) for line in raw[:complete].splitlines() if line.strip()]
                offset += complete
                self.messages.put(("snapshot", (target, active, events, device_epoch_origin)))
                last_error = None
                if self.replay_path:
                    return
            except (OSError, ValueError, subprocess.TimeoutExpired) as error:
                # Some ADB versions return shell errors as successful text.
                # Keep the status even when reading or parsing events fails.
                if active is not None:
                    self.messages.put(("snapshot", (target, active, [], device_epoch_origin)))
                text = "设备记录读取失败：" + str(error)
                if text != last_error:
                    self.messages.put(("notice", (target, text)))
                    last_error = text
            self.monitor_stop.wait(1)

    def is_live(self):
        return not self.replay_path and self.active.get("state") == "running"

    def save_options(self):
        path = PROJECT/"work/window.json"
        path.parent.mkdir(exist_ok=True)
        path.write_text(json.dumps(self.options, ensure_ascii=False), encoding="utf-8")

    def on_mode(self):
        self.mode_before = self.options.get("scout", "Red")
        self.options["scout"] = "Blue" if self.blue_scout.get() else "Red"
        self.save_options()
        if self.is_live():
            self.mode_pending = True
            self.mode_requested = self.options["scout"]
            self._spawn_command("SetMode")
        self._render(False)

    def on_start(self):
        if self.replay_path or self.is_live() or self.launch_pending or self.command_pending or self.mode_pending:
            return
        self.before_start = dict(self.active)
        self.launch_pending = self.command_pending = True
        self.stop_requested = self.close_requested = False
        self.stop_sent = False
        self.notice = ""
        self._spawn_command("Start")
        self._render()

    def on_stop(self, close_after=False):
        self.close_requested = self.close_requested or close_after
        if self.replay_path:
            if close_after:
                self._close()
            return
        if self.is_live() or self.launch_pending or self.command_pending:
            self.stop_requested = True
        elif close_after and not self.stop_command_pending:
            self._close()

    def _spawn_command(self, action):
        args = [POWERSHELL, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
                str(PROJECT/"Control.ps1"), "-Action", action, "-Target", self.options["target"]]
        if action == "Start":
            for key, flag in (("levels", "StopAfterLevels"), ("scout", "ScoutMode")):
                args += ["-"+flag, str(self.options[key])]
        elif action == "SetMode":
            args += ["-ScoutMode", self.options["scout"]]
        def command():
            try:
                result = run_hidden(args)
                text = (result.stdout+result.stderr).decode("utf-8-sig", errors="replace").strip()
                self.messages.put(("command", (action, result.returncode, text)))
            except (OSError, subprocess.TimeoutExpired) as error:
                self.messages.put(("command", (action, -1, str(error))))
        threading.Thread(target=command, daemon=True).start()

    def _poll(self):
        changed = False
        while not self.messages.empty():
            kind, data = self.messages.get_nowait()
            changed = True
            if kind == "snapshot":
                target, active, events, device_epoch_origin = data
                if target != self.options["target"]:
                    continue
                for event in events:
                    if event.get("event") in ("started", "initial"):
                        self.model = ViewSnapshot()
                    self.model.apply(event, device_epoch_origin)
                if active is not None:
                    self.active = active
                    # The checkbox follows preference, not the temporary effective mode.
                    mode = active.get("scoutMode")
                    if self.is_live() and mode in ("Red", "Blue") and not self.mode_pending and self.mode_requested in (None, mode):
                        self.mode_requested = None
                        self.options["scout"] = mode
                        self.blue_scout.set(mode == "Blue")
                    elif not self.is_live():
                        self.mode_requested = None
                    if self.launch_pending and active != self.before_start:
                        self.launch_pending = False
                    self.model.last_elapsed_ms = max(self.model.last_elapsed_ms, active.get("elapsedMs", 0))
                    self.model.phase = active.get("phase", self.model.phase)
                self.notice = ""
            elif kind == "notice":
                target, text = data
                if target == self.options["target"]:
                    self.notice = text
            elif kind == "command":
                action, code, text = data
                if action == "Start":
                    self.command_pending = False
                    if code:
                        self.launch_pending = False
                elif action == "SetMode":
                    self.mode_pending = False
                    if code:
                        self.mode_requested = None
                        self.options["scout"] = self.mode_before
                        self.blue_scout.set(self.mode_before == "Blue")
                        self.save_options()
                else:
                    self.stop_command_pending = False
                if code:
                    self.notice = text or "控制命令执行失败"
                    self.model.logs.append(self.notice)
                    if action != "SetMode":
                        self.stop_requested = self.close_requested = False
                        self.stop_sent = False
                else:
                    self.model.logs.append({"Start": "已发送启动命令，等待设备状态。", "Stop": "已请求停止，等待设备收尾。",
                                           "SetMode": "已提交侦察模式，下一次侦察生效。"}[action])
        if self.stop_requested and not self.stop_command_pending:
            # Wait for a start in flight: the device launcher clears its stop file.
            if self.is_live() and not self.command_pending and not self.launch_pending and not self.stop_sent:
                self.stop_sent = True
                self.stop_command_pending = True
                self._spawn_command("Stop")
            elif not self.is_live() and not self.command_pending and not self.launch_pending:
                self.stop_requested = False
        if self.close_requested and not (self.is_live() or self.launch_pending or self.command_pending or self.stop_command_pending or self.mode_pending):
            self._close()
            return
        self._render(redraw=changed)
        self.root.after(100, self._poll)

    def _render(self, redraw=True):
        live = self.is_live()
        source = bool(self.model.board_event)
        if self.replay_path:
            label = "历史回放 · 只读；开始与停止已禁用"
        elif self.launch_pending or self.command_pending:
            label = "正在启动 · 下方旧记录仍是上次快照"
        elif live:
            label = "正在停止，等待设备收尾…" if self.stop_requested or self.close_requested or self.stop_command_pending else "运行中 · 显示运行状态快照"
        elif source:
            label = "已停止 · 最后快照（不是当前账号的实时棋盘）"
        else:
            label = "尚未运行 · 当前账号棋盘未知"
        target = TARGETS[self.options["target"]]
        mode = scout_label(self.active if live else None, self.options.get("scout", "Red"))
        self.status.set(self.notice or f"{label} · {target} · {mode}")
        self.level.set(("" if live else "历史 · ")+f"本次第 {self.model.level_index} 关" if source else "尚未读取")
        self.phase.set(self.model.phase)
        seconds = self.model.level_seconds(live)
        self.duration.set(f"{seconds:.1f} 秒" if seconds is not None else "旧记录未提供")
        if self.model.prepared_target:
            self.target.set("准备点击：第 %d 行，第 %d 列" % self.model.prepared_target)
        elif self.model.actual_target:
            self.target.set("最近结果目标：第 %d 行，第 %d 列" % self.model.actual_target)
        else:
            self.target.set("尚无目标记录")
        busy = live or self.launch_pending or self.command_pending or self.stop_command_pending
        self.start_button.configure(state="disabled" if self.replay_path or busy else "normal")
        self.stop_button.configure(state="normal" if not self.replay_path and busy else "disabled")
        self.mode_button.configure(state="disabled" if self.replay_path or self.launch_pending or self.command_pending or self.mode_pending or self.stop_requested else "normal")
        logs = "\n".join(self.model.logs)
        if logs != self._last_logs:
            self.log_box.configure(state="normal")
            self.log_box.delete("1.0", "end")
            self.log_box.insert("end", logs or "等待运行日志…")
            self.log_box.configure(state="disabled")
            self.log_box.see("end")
            self._last_logs = logs
        if redraw:
            self._draw_board()

    def _draw_board(self):
        canvas = self.canvas
        canvas.delete("all")
        width,height = max(1,canvas.winfo_width()),max(1,canvas.winfo_height())
        cells, problem = board_polygons(self.model.board_event,width,height)
        if not cells:
            canvas.create_text(width/2,height/2,text=problem,fill=MUTED,font=("Microsoft YaHei UI",11),width=max(100,width-50))
            return
        opened = self.model.board_event.get("opened") or []
        for cell in cells:
            r,c = cell["r"],cell["c"]
            is_open = len(opened)>=r and isinstance(opened[r-1],list) and len(opened[r-1])>=c and opened[r-1][c-1] is True
            fill = OPEN_COLOR if is_open else CELL_COLORS.get(cell["value"],CELL_COLORS[-1])
            outline,line_width = "#172433",1
            if f"{r},{c}" in self.model.confirmed_batch:
                outline,line_width = CONFIRMED_COLOR,3
            if self.model.actual_target == (r,c):
                outline,line_width = TARGET_COLOR,3
            canvas.create_polygon(*[v for point in cell["corners"] for v in point],fill=fill,outline=outline,width=line_width)
            canvas.create_text(*cell["center"],text=f"{r},{c}",fill="#edf3f8",font=("Segoe UI",8))

    def settings(self):
        if self.replay_path or self.is_live() or self.launch_pending or self.command_pending or self.stop_command_pending or self.mode_pending:
            return
        dialog = tk.Toplevel(self.root)
        dialog.title("V2 声纳 · 运行设置")
        dialog.resizable(False, False)
        dialog.transient(self.root)
        pane = ttk.Frame(dialog, padding=20)
        pane.pack()
        levels = tk.StringVar(value=str(self.options["levels"]))
        ttk.Label(pane, text="完成关数（0 为连续运行）").grid(row=0, column=0, sticky="w", padx=(0,20), pady=7)
        ttk.Entry(pane, textvariable=levels, width=22).grid(row=0, column=1)
        def save():
            try:
                values = {"levels": int(levels.get())}
                if values["levels"] < 0:
                    raise ValueError()
            except ValueError:
                messagebox.showerror("运行设置", "完成关数请填写非负整数。", parent=dialog)
                return
            if self.is_live() or self.launch_pending or self.command_pending or self.stop_command_pending or self.mode_pending:
                messagebox.showerror("运行设置", "请先停止脚本并等待收尾。", parent=dialog)
                return
            self.options.update(values)
            self.save_options()
            dialog.destroy()
            self._render(False)
        ttk.Button(pane, text="保存", command=save).grid(row=1, column=1, sticky="e")
        dialog.grab_set()

    def _close(self):
        self.monitor_stop.set()
        self.root.destroy()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--events", type=Path, help="只读历史记录；禁用开始与停止")
    parser.add_argument("--target", choices=tuple(TARGETS))
    parser.add_argument("--levels", type=int)
    parser.add_argument("--scout", choices=("Red", "Blue"))
    args = parser.parse_args()
    if not args.events and not LOCAL:
        parser.error("请按 README 创建 local.json 并填写本机工具路径。")
    options = dict(target="International", levels=0, scout="Red")
    config = PROJECT/"work/window.json"
    if config.exists():
        options.update({key: value for key, value in json.loads(config.read_text(encoding="utf-8")).items() if key in options})
    options.update({key: value for key, value in vars(args).items() if key != "events" and value is not None})
    if options["target"] not in TARGETS:
        options["target"] = "International"
    root = tk.Tk()
    RuntimeWindow(root, options, args.events)
    root.mainloop()


if __name__ == "__main__":
    main()
