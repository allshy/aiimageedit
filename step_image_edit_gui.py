import base64
import json
import mimetypes
import os
import subprocess
import threading
import time
import traceback
import zlib
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path
from tkinter import BooleanVar, StringVar, Tk, filedialog, messagebox
from tkinter import ttk
from tkinter.scrolledtext import ScrolledText


API_BASE_URL = "https://api.stepfun.com/v1"
MODEL_NAME = "step-image-edit-2"
APP_DIR = Path(__file__).resolve().parent
OUTPUT_DIR = APP_DIR / "outputs"
LOG_FILE = APP_DIR / "step_image_edit_gui.log"
ENV_FILE = APP_DIR / ".env"
RESIZE_SCRIPT = APP_DIR / "resize_image.ps1"
REQUEST_TIMEOUT = 75


def log_message(message):
    stamp = time.strftime("%Y-%m-%d %H:%M:%S")
    with LOG_FILE.open("a", encoding="utf-8") as f:
        f.write(f"[{stamp}] {message}\n")


def load_env_key():
    key = os.environ.get("STEPFUN_API_KEY", "").strip()
    if key:
        return key

    if not ENV_FILE.exists():
        return ""

    for line in ENV_FILE.read_text(encoding="utf-8", errors="ignore").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        name, value = line.split("=", 1)
        if name.strip() == "STEPFUN_API_KEY":
            return value.strip().strip('"').strip("'")
    return ""


def save_env_key(api_key):
    api_key = api_key.strip()
    if not api_key:
        raise ValueError("API Key 为空，无法保存。")
    ENV_FILE.write_text(f"STEPFUN_API_KEY={api_key}\n", encoding="utf-8")


def delete_env_key():
    if ENV_FILE.exists():
        ENV_FILE.unlink()


def normalize_upload_mode(label):
    if label == "无损PNG":
        return "png"
    if label == "原图":
        return "original"
    return "jpg95"


def guess_mime(path):
    guessed, _ = mimetypes.guess_type(path)
    return guessed or "application/octet-stream"


def make_test_png(path):
    width = 256
    height = 256
    raw = bytearray()
    for y in range(height):
        raw.append(0)
        for x in range(width):
            if 72 <= x <= 184 and 72 <= y <= 184:
                raw.extend((42, 126, 214))
            else:
                raw.extend((246, 248, 250))

    def chunk(kind, data):
        payload = kind + data
        return (
            len(data).to_bytes(4, "big")
            + payload
            + zlib.crc32(payload).to_bytes(4, "big")
        )

    png = (
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", width.to_bytes(4, "big") + height.to_bytes(4, "big") + b"\x08\x02\x00\x00\x00")
        + chunk(b"IDAT", zlib.compress(bytes(raw), level=6))
        + chunk(b"IEND", b"")
    )
    path.write_bytes(png)
    return path


def prepare_upload_image(path, upload_mode, max_side):
    image_path = Path(path)
    if upload_mode == "original":
        log_message(f"upload original: {image_path.name} ({image_path.stat().st_size / 1024 / 1024:.2f}MB)")
        return str(image_path)

    suffix = image_path.suffix.lower()
    if suffix not in {".jpg", ".jpeg", ".png", ".bmp"}:
        log_message(f"skip resize for unsupported format: {image_path.name}")
        return str(image_path)

    try:
        max_side = int(max_side)
    except ValueError:
        max_side = 1536

    cache_dir = OUTPUT_DIR / "upload-cache"
    cache_dir.mkdir(parents=True, exist_ok=True)
    if upload_mode == "png":
        out_path = cache_dir / f"{image_path.stem}-{max_side}.png"
        output_format = "png"
        quality = "100"
    else:
        out_path = cache_dir / f"{image_path.stem}-{max_side}-q95.jpg"
        output_format = "jpg"
        quality = "95"
    cmd = [
        "powershell",
        "-NoProfile",
        "-ExecutionPolicy",
        "Bypass",
        "-File",
        str(RESIZE_SCRIPT),
        "-InputPath",
        str(image_path),
        "-OutputPath",
        str(out_path),
        "-MaxSide",
        str(max_side),
        "-Quality",
        quality,
        "-Format",
        output_format,
    ]
    proc = subprocess.run(cmd, capture_output=True, text=True, timeout=60)
    if proc.returncode != 0:
        log_message(f"resize failed: {proc.stderr.strip() or proc.stdout.strip()}")
        return str(image_path)

    summary = proc.stdout.strip()
    log_message(f"upload preprocess summary: mode={upload_mode}; {summary}")
    if out_path.exists():
        return str(out_path)
    return str(image_path)


def build_multipart(fields, files):
    boundary = "----stepfun-boundary-" + uuid.uuid4().hex
    body = bytearray()

    for name, value in fields.items():
        if value is None or value == "":
            continue
        body.extend(f"--{boundary}\r\n".encode("utf-8"))
        body.extend(
            f'Content-Disposition: form-data; name="{name}"\r\n\r\n'.encode("utf-8")
        )
        body.extend(str(value).encode("utf-8"))
        body.extend(b"\r\n")

    for index, (name, path) in enumerate(files.items(), start=1):
        if not path:
            continue
        file_path = Path(path)
        suffix = file_path.suffix.lower()
        if suffix not in {".png", ".jpg", ".jpeg", ".webp"}:
            suffix = ".png"
        upload_name = f"{name}_{index}{suffix}"
        body.extend(f"--{boundary}\r\n".encode("utf-8"))
        body.extend(
            (
                f'Content-Disposition: form-data; name="{name}"; '
                f'filename="{upload_name}"\r\n'
            ).encode("utf-8")
        )
        body.extend(f"Content-Type: {guess_mime(file_path)}\r\n\r\n".encode("utf-8"))
        body.extend(file_path.read_bytes())
        body.extend(b"\r\n")

    body.extend(f"--{boundary}--\r\n".encode("utf-8"))
    return bytes(body), f"multipart/form-data; boundary={boundary}"


def request_json(url, api_key, payload, timeout):
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=body,
        method="POST",
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": "application/json",
        },
    )
    return send_request(req, timeout)


def request_multipart(url, api_key, fields, files, timeout):
    body, content_type = build_multipart(fields, files)
    file_summary = ", ".join(
        f"{field}={Path(path).name}({Path(path).stat().st_size / 1024 / 1024:.2f}MB)"
        for field, path in files.items()
        if path
    )
    log_message(
        "multipart fields="
        + ",".join(fields.keys())
        + f"; files={file_summary}; body={len(body) / 1024 / 1024:.2f}MB"
    )
    req = urllib.request.Request(
        url,
        data=body,
        method="POST",
        headers={
            "Authorization": f"Bearer {api_key}",
            "Content-Type": content_type,
        },
    )
    return send_request(req, timeout)


def send_request(req, timeout):
    try:
        proxies = urllib.request.getproxies()
        log_message(f"POST {req.full_url}; proxies={proxies}")
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read()
            content_type = resp.headers.get("Content-Type", "")
            log_message(f"response status={resp.status}; content_type={content_type}; bytes={len(raw)}")
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")
        log_message(f"HTTP {exc.code}: {detail}")
        raise RuntimeError(f"HTTP {exc.code}: {detail}") from exc
    except urllib.error.URLError as exc:
        log_message(f"Network error: {exc}")
        raise RuntimeError(f"网络请求失败或超时：{exc}") from exc
    except TimeoutError as exc:
        log_message("Request timed out")
        raise RuntimeError(f"请求超过 {timeout} 秒仍未返回，已自动停止。") from exc

    if "application/json" not in content_type:
        return {"raw_bytes": raw, "content_type": content_type}

    return json.loads(raw.decode("utf-8"))


def save_result(response, prefix, timeout):
    OUTPUT_DIR.mkdir(exist_ok=True)
    stamp = time.strftime("%Y%m%d-%H%M%S")

    if isinstance(response, dict) and response.get("raw_bytes") is not None:
        suffix = ".png"
        content_type = response.get("content_type", "")
        if "jpeg" in content_type or "jpg" in content_type:
            suffix = ".jpg"
        elif "webp" in content_type:
            suffix = ".webp"
        out = OUTPUT_DIR / f"{prefix}-{stamp}{suffix}"
        out.write_bytes(response["raw_bytes"])
        return out

    data = response.get("data") if isinstance(response, dict) else None
    if not data:
        raise RuntimeError("API response did not include a data array.")

    item = data[0]
    if item.get("b64_json"):
        image_bytes = base64.b64decode(item["b64_json"])
        out = OUTPUT_DIR / f"{prefix}-{stamp}.png"
        out.write_bytes(image_bytes)
        return out

    if item.get("url"):
        image_url = item["url"]
        with urllib.request.urlopen(image_url, timeout=timeout) as resp:
            image_bytes = resp.read()
            content_type = resp.headers.get("Content-Type", "")
        suffix = ".png"
        if "jpeg" in content_type or "jpg" in content_type:
            suffix = ".jpg"
        elif "webp" in content_type:
            suffix = ".webp"
        out = OUTPUT_DIR / f"{prefix}-{stamp}{suffix}"
        out.write_bytes(image_bytes)
        return out

    raise RuntimeError("API response did not include b64_json or url.")


class StepImageEditApp:
    def __init__(self, root):
        self.root = root
        self.root.title("Step Image Edit 2")
        self.root.geometry("1040x760")
        self.root.minsize(900, 640)

        self.api_key = StringVar(value=load_env_key())
        self.image_path = StringVar()
        self.mask_path = StringVar()
        self.size = StringVar(value="1024x1024")
        self.response_format = StringVar(value="b64_json")
        self.output_path = StringVar()
        self.key_status = StringVar(value="已从本地读取" if self.api_key.get() else "未保存")
        self.image_info = StringVar(value="未选择图片")
        self.use_mask = BooleanVar(value=False)
        self.auto_resize = BooleanVar(value=True)
        self.remember_key = BooleanVar(value=True)
        self.max_side = StringVar(value="2048")
        self.upload_mode = StringVar(value="高质JPG")
        self.timeout_seconds = StringVar(value="180")
        self.busy = False

        self.create_widgets()

    def create_widgets(self):
        style = ttk.Style()
        try:
            style.theme_use("clam")
        except Exception:
            pass
        style.configure("Title.TLabel", font=("Microsoft YaHei UI", 16, "bold"))
        style.configure("Hint.TLabel", foreground="#555555")

        root_frame = ttk.Frame(self.root, padding=16)
        root_frame.pack(fill="both", expand=True)

        header = ttk.Frame(root_frame)
        header.pack(fill="x", pady=(0, 12))
        ttk.Label(header, text="Step Image Edit 2", style="Title.TLabel").pack(side="left")
        ttk.Label(header, text="本地图片编辑工具", style="Hint.TLabel").pack(
            side="left", padx=(12, 0)
        )

        api_frame = ttk.LabelFrame(root_frame, text="API")
        api_frame.pack(fill="x")
        ttk.Label(api_frame, text="API Key").grid(row=0, column=0, sticky="w", padx=10, pady=10)
        self.api_entry = ttk.Entry(api_frame, textvariable=self.api_key, show="*", width=58)
        self.api_entry.grid(row=0, column=1, sticky="ew", padx=10, pady=10)
        ttk.Checkbutton(api_frame, text="自动保存", variable=self.remember_key).grid(
            row=0, column=2, sticky="w", padx=(0, 10), pady=10
        )
        ttk.Button(api_frame, text="保存", command=self.save_key_clicked).grid(
            row=0, column=3, padx=4, pady=10
        )
        ttk.Button(api_frame, text="清除", command=self.clear_key_clicked).grid(
            row=0, column=4, padx=4, pady=10
        )
        ttk.Button(api_frame, text="测试 API", command=self.start_api_test).grid(
            row=0, column=5, sticky="e", padx=10, pady=10
        )
        ttk.Label(api_frame, textvariable=self.key_status, style="Hint.TLabel").grid(
            row=1, column=1, columnspan=5, sticky="w", padx=10, pady=(0, 10)
        )
        api_frame.columnconfigure(1, weight=1)

        self.edit_tab = ttk.LabelFrame(root_frame, text="图片编辑", padding=14)
        self.edit_tab.pack(fill="both", expand=True, pady=(12, 0))
        self.create_edit_tab()
        self.create_output_panel(root_frame)

    def create_edit_tab(self):
        file_frame = ttk.Frame(self.edit_tab)
        file_frame.pack(fill="x")

        ttk.Label(file_frame, text="原图").grid(row=0, column=0, sticky="w", pady=6)
        ttk.Entry(file_frame, textvariable=self.image_path).grid(
            row=0, column=1, sticky="ew", padx=8, pady=6
        )
        ttk.Button(file_frame, text="选择", command=self.pick_image).grid(
            row=0, column=2, padx=4, pady=6
        )
        ttk.Label(file_frame, textvariable=self.image_info, style="Hint.TLabel").grid(
            row=2, column=1, sticky="w", padx=8, pady=(0, 4)
        )

        ttk.Checkbutton(file_frame, text="使用蒙版", variable=self.use_mask).grid(
            row=1, column=0, sticky="w", pady=6
        )
        ttk.Entry(file_frame, textvariable=self.mask_path).grid(
            row=1, column=1, sticky="ew", padx=8, pady=6
        )
        ttk.Button(file_frame, text="选择", command=self.pick_mask).grid(
            row=1, column=2, padx=4, pady=6
        )
        file_frame.columnconfigure(1, weight=1)

        settings = ttk.Frame(self.edit_tab)
        settings.pack(fill="x", pady=(8, 0))
        ttk.Label(settings, text="尺寸").pack(side="left")
        ttk.Combobox(
            settings,
            textvariable=self.size,
            values=["1024x1024", "1024x768", "768x1024", "512x512"],
            width=14,
            state="readonly",
        ).pack(side="left", padx=(8, 20))
        ttk.Label(settings, text="返回格式").pack(side="left")
        ttk.Combobox(
            settings,
            textvariable=self.response_format,
            values=["b64_json", "url"],
            width=12,
            state="readonly",
        ).pack(side="left", padx=8)
        ttk.Checkbutton(settings, text="预处理上传", variable=self.auto_resize).pack(
            side="left", padx=(18, 6)
        )
        ttk.Combobox(
            settings,
            textvariable=self.upload_mode,
            values=["高质JPG", "无损PNG", "原图"],
            width=10,
            state="readonly",
        ).pack(side="left", padx=(0, 10))
        ttk.Label(settings, text="长边").pack(side="left")
        ttk.Combobox(
            settings,
            textvariable=self.max_side,
            values=["1536", "2048", "3072", "4096"],
            width=8,
            state="readonly",
        ).pack(side="left", padx=8)
        ttk.Label(settings, text="超时").pack(side="left", padx=(12, 0))
        ttk.Combobox(
            settings,
            textvariable=self.timeout_seconds,
            values=["75", "120", "180", "300", "600"],
            width=8,
            state="readonly",
        ).pack(side="left", padx=8)

        ttk.Label(self.edit_tab, text="编辑提示词").pack(anchor="w", pady=(14, 6))
        self.edit_prompt = ScrolledText(self.edit_tab, height=8, wrap="word")
        self.edit_prompt.pack(fill="both", expand=True)
        self.edit_prompt.insert("1.0", "把背景换成干净的白色摄影棚，保持主体不变")

        action_row = ttk.Frame(self.edit_tab)
        action_row.pack(fill="x", pady=(12, 0))
        ttk.Button(action_row, text="清空提示词", command=self.clear_prompt).pack(side="left")
        self.edit_button = ttk.Button(action_row, text="开始编辑", command=self.start_edit)
        self.edit_button.pack(side="right")

    def create_generate_tab(self):
        settings = ttk.Frame(self.generate_tab)
        settings.pack(fill="x")
        ttk.Label(settings, text="尺寸").pack(side="left")
        ttk.Combobox(
            settings,
            textvariable=self.size,
            values=["1024x1024", "1024x768", "768x1024", "512x512"],
            width=14,
            state="readonly",
        ).pack(side="left", padx=(8, 20))
        ttk.Label(settings, text="返回格式").pack(side="left")
        ttk.Combobox(
            settings,
            textvariable=self.response_format,
            values=["b64_json", "url"],
            width=12,
            state="readonly",
        ).pack(side="left", padx=8)

        ttk.Label(self.generate_tab, text="生成提示词").pack(anchor="w", pady=(14, 6))
        self.generate_prompt = ScrolledText(self.generate_tab, height=10, wrap="word")
        self.generate_prompt.pack(fill="both", expand=True)
        self.generate_prompt.insert("1.0", "一张产品海报，简洁明亮，高清摄影风格")

        ttk.Button(self.generate_tab, text="开始生成", command=self.start_generate).pack(
            anchor="e", pady=(12, 0)
        )

    def create_output_panel(self, root_frame):
        output = ttk.LabelFrame(root_frame, text="输出")
        output.pack(fill="x", pady=(14, 0))

        ttk.Entry(output, textvariable=self.output_path).grid(
            row=0, column=0, sticky="ew", padx=10, pady=10
        )
        ttk.Button(output, text="打开结果", command=self.open_output).grid(
            row=0, column=1, padx=4, pady=10
        )
        ttk.Button(output, text="打开文件夹", command=self.open_output_dir).grid(
            row=0, column=2, padx=10, pady=10
        )
        ttk.Button(output, text="查看日志", command=self.open_log).grid(
            row=0, column=3, padx=(0, 10), pady=10
        )
        output.columnconfigure(0, weight=1)

        self.status = ttk.Label(root_frame, text="准备就绪")
        self.status.pack(anchor="w", pady=(10, 0))
        self.progress = ttk.Progressbar(root_frame, mode="indeterminate")
        self.progress.pack(fill="x", pady=(8, 0))

    def pick_image(self):
        path = filedialog.askopenfilename(
            title="选择原图",
            filetypes=[
                ("Image files", "*.png *.jpg *.jpeg *.webp"),
                ("All files", "*.*"),
            ],
        )
        if path:
            self.image_path.set(path)
            self.update_image_info(path)

    def pick_mask(self):
        path = filedialog.askopenfilename(
            title="选择蒙版",
            filetypes=[
                ("Image files", "*.png *.jpg *.jpeg *.webp"),
                ("All files", "*.*"),
            ],
        )
        if path:
            self.mask_path.set(path)
            self.use_mask.set(True)

    def get_api_key(self):
        key = self.api_key.get().strip()
        if not key:
            raise ValueError("请填写 API Key，或先设置 STEPFUN_API_KEY 环境变量。")
        return key

    def maybe_save_api_key(self, key):
        if not self.remember_key.get():
            return
        save_env_key(key)
        self.key_status.set("已保存到本地 .env")

    def save_key_clicked(self):
        try:
            key = self.get_api_key()
            save_env_key(key)
        except Exception as exc:
            messagebox.showwarning("保存失败", str(exc))
            return
        self.key_status.set("已保存到本地 .env")
        messagebox.showinfo("已保存", "API Key 已保存到当前文件夹的 .env。")

    def clear_key_clicked(self):
        delete_env_key()
        self.api_key.set("")
        self.key_status.set("已清除本地保存")
        messagebox.showinfo("已清除", "已删除本地保存的 API Key。")

    def update_image_info(self, path):
        file_path = Path(path)
        if not file_path.exists():
            self.image_info.set("图片不存在")
            return
        size_mb = file_path.stat().st_size / 1024 / 1024
        self.image_info.set(f"{file_path.name} | {size_mb:.2f} MB")

    def clear_prompt(self):
        self.edit_prompt.delete("1.0", "end")

    def start_edit(self):
        if self.busy:
            return
        try:
            key = self.get_api_key()
        except ValueError as exc:
            messagebox.showwarning("缺少 API Key", str(exc))
            return
        self.maybe_save_api_key(key)
        prompt = self.edit_prompt.get("1.0", "end").strip()
        image = self.image_path.get().strip()
        mask = self.mask_path.get().strip()
        use_mask = self.use_mask.get()
        response_format = self.response_format.get()
        auto_resize = self.auto_resize.get()
        max_side = self.max_side.get()
        upload_mode = normalize_upload_mode(self.upload_mode.get()) if auto_resize else "original"
        timeout = int(self.timeout_seconds.get())
        if not image:
            messagebox.showwarning("缺少原图", "请先选择要编辑的图片。")
            return
        if not Path(image).exists():
            messagebox.showwarning("图片不存在", "选择的原图文件不存在。")
            return
        if not prompt:
            messagebox.showwarning("缺少提示词", "请输入编辑提示词。")
            return
        if len(prompt) > 512:
            messagebox.showwarning("提示词太长", "Step Image Edit 2 的提示词最多 512 个字符。")
            return
        if use_mask and mask and not Path(mask).exists():
            messagebox.showwarning("蒙版不存在", "选择的蒙版文件不存在。")
            return

        config = {
            "api_key": key,
            "prompt": prompt,
            "image": image,
            "mask": mask if use_mask else "",
            "response_format": response_format,
            "upload_mode": upload_mode,
            "max_side": max_side,
            "timeout": timeout,
        }
        self.run_in_thread(lambda: self.edit_image(config))

    def start_generate(self):
        if self.busy:
            return
        try:
            key = self.get_api_key()
        except ValueError as exc:
            messagebox.showwarning("缺少 API Key", str(exc))
            return
        self.maybe_save_api_key(key)
        prompt = self.generate_prompt.get("1.0", "end").strip()
        size = self.size.get()
        response_format = self.response_format.get()
        if not prompt:
            messagebox.showwarning("缺少提示词", "请输入生成提示词。")
            return
        if len(prompt) > 512:
            messagebox.showwarning("提示词太长", "提示词最多 512 个字符。")
            return
        config = {
            "api_key": key,
            "prompt": prompt,
            "size": size,
            "response_format": response_format,
            "timeout": int(self.timeout_seconds.get()),
        }
        self.run_in_thread(lambda: self.generate_image(config))

    def start_api_test(self):
        if self.busy:
            return
        try:
            key = self.get_api_key()
        except ValueError as exc:
            messagebox.showwarning("缺少 API Key", str(exc))
            return
        self.maybe_save_api_key(key)
        test_image = OUTPUT_DIR / "api-test-input.png"
        OUTPUT_DIR.mkdir(exist_ok=True)
        make_test_png(test_image)
        config = {
            "api_key": key,
            "prompt": "把蓝色方块改成红色方块",
            "image": str(test_image),
            "mask": "",
            "response_format": "b64_json",
            "upload_mode": "original",
            "timeout": int(self.timeout_seconds.get()),
            "is_test": True,
        }
        self.run_in_thread(lambda: self.edit_image(config))

    def run_in_thread(self, target):
        self.busy = True
        self.status.configure(text="请求中，请稍候...")
        self.edit_button.configure(state="disabled")
        self.progress.start(12)
        thread = threading.Thread(target=self.safe_run, args=(target,), daemon=True)
        thread.start()

    def safe_run(self, target):
        try:
            result_path = target()
            self.root.after(0, lambda: self.finish_success(result_path))
        except Exception as exc:
            details = "".join(traceback.format_exception_only(type(exc), exc)).strip()
            self.root.after(0, lambda: self.finish_error(details))

    def edit_image(self, config):
        fields = {
            "model": MODEL_NAME,
            "prompt": config["prompt"],
            "response_format": config["response_format"],
            "cfg_scale": "1.0",
            "steps": "8",
        }
        if config.get("is_test"):
            fields["seed"] = "1"
        files = {"image": config["image"]}
        files["image"] = prepare_upload_image(
            config["image"],
            config.get("upload_mode", "original"),
            config.get("max_side", "1536"),
        )
        if config["mask"]:
            files["mask"] = config["mask"]

        response = request_multipart(
            f"{API_BASE_URL}/images/edits",
            config["api_key"],
            fields,
            files,
            config.get("timeout", REQUEST_TIMEOUT),
        )
        return save_result(response, "edit", config.get("timeout", REQUEST_TIMEOUT))

    def generate_image(self, config):
        payload = {
            "model": MODEL_NAME,
            "prompt": config["prompt"],
            "size": config["size"],
            "response_format": config["response_format"],
        }
        response = request_json(
            f"{API_BASE_URL}/images/generations",
            config["api_key"],
            payload,
            config.get("timeout", REQUEST_TIMEOUT),
        )
        return save_result(response, "generate", config.get("timeout", REQUEST_TIMEOUT))

    def finish_success(self, result_path):
        self.busy = False
        self.edit_button.configure(state="normal")
        self.progress.stop()
        self.output_path.set(str(result_path))
        self.status.configure(text="完成")
        try:
            os.startfile(result_path)
        except OSError:
            pass

    def finish_error(self, message):
        self.busy = False
        self.edit_button.configure(state="normal")
        self.progress.stop()
        self.status.configure(text="失败")
        messagebox.showerror("请求失败", message)

    def open_output(self):
        path = self.output_path.get().strip()
        if path and Path(path).exists():
            os.startfile(path)
        else:
            messagebox.showinfo("没有结果", "还没有可打开的输出图片。")

    def open_output_dir(self):
        OUTPUT_DIR.mkdir(exist_ok=True)
        os.startfile(OUTPUT_DIR)

    def open_log(self):
        if not LOG_FILE.exists():
            LOG_FILE.write_text("", encoding="utf-8")
        os.startfile(LOG_FILE)


def main():
    OUTPUT_DIR.mkdir(exist_ok=True)
    root = Tk()
    try:
        root.call("tk", "scaling", 1.2)
    except Exception:
        pass
    app = StepImageEditApp(root)
    root.mainloop()


if __name__ == "__main__":
    main()
