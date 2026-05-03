import base64
import json
import mimetypes
import os
import subprocess
import threading
import time
import traceback
import zlib
import struct
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
SUPPORTED_IMAGE_EXTS = {".png", ".jpg", ".jpeg", ".webp"}


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
    if label == "智能高质量":
        return "smart"
    if label == "无损PNG":
        return "png"
    if label == "原图":
        return "original"
    return "jpg95"


def normalize_quality_mode(label):
    if label == "精细":
        return "16"
    if label == "更稳":
        return "12"
    return "8"


def guess_mime(path):
    guessed, _ = mimetypes.guess_type(path)
    return guessed or "application/octet-stream"


def describe_image_file(path):
    file_path = Path(path)
    if not file_path.exists():
        return "文件不存在"
    suffix = file_path.suffix.lower().lstrip(".") or "unknown"
    size_mb = file_path.stat().st_size / 1024 / 1024
    dimensions = get_image_dimensions(file_path)
    if dimensions:
        return f"{file_path.name} | {suffix.upper()} | {dimensions[0]}x{dimensions[1]} | {size_mb:.2f} MB"
    return f"{file_path.name} | {suffix.upper()} | {size_mb:.2f} MB"


def unique_path(path):
    path = Path(path)
    if not path.exists():
        return path
    for index in range(1, 10000):
        candidate = path.with_name(f"{path.stem}-{index}{path.suffix}")
        if not candidate.exists():
            return candidate
    raise RuntimeError(f"无法生成唯一文件名：{path}")


def safe_stem(path):
    stem = Path(path).stem.strip()
    cleaned = "".join(ch if ch.isalnum() or ch in "-_." else "_" for ch in stem)
    return cleaned or "image"


def get_image_dimensions(path):
    file_path = Path(path)
    suffix = file_path.suffix.lower()
    try:
        with file_path.open("rb") as f:
            if suffix == ".png":
                header = f.read(24)
                if header.startswith(b"\x89PNG\r\n\x1a\n"):
                    return struct.unpack(">II", header[16:24])

            if suffix in {".jpg", ".jpeg"}:
                if f.read(2) != b"\xff\xd8":
                    return None
                while True:
                    marker_prefix = f.read(1)
                    if not marker_prefix:
                        return None
                    if marker_prefix != b"\xff":
                        continue
                    marker = f.read(1)
                    while marker == b"\xff":
                        marker = f.read(1)
                    if marker in {b"\xd8", b"\xd9"}:
                        continue
                    length_bytes = f.read(2)
                    if len(length_bytes) != 2:
                        return None
                    length = struct.unpack(">H", length_bytes)[0]
                    if length < 2:
                        return None
                    if marker[0] in {
                        0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7,
                        0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF,
                    }:
                        data = f.read(5)
                        if len(data) != 5:
                            return None
                        height, width = struct.unpack(">HH", data[1:5])
                        return width, height
                    f.seek(length - 2, 1)

            if suffix == ".webp":
                riff = f.read(12)
                if len(riff) != 12 or riff[:4] != b"RIFF" or riff[8:12] != b"WEBP":
                    return None
                chunk = f.read(8)
                if len(chunk) != 8:
                    return None
                chunk_type = chunk[:4]
                chunk_size = struct.unpack("<I", chunk[4:8])[0]
                data = f.read(chunk_size)
                if chunk_type == b"VP8X" and len(data) >= 10:
                    width = 1 + int.from_bytes(data[4:7], "little")
                    height = 1 + int.from_bytes(data[7:10], "little")
                    return width, height
                if chunk_type == b"VP8 " and len(data) >= 10:
                    width = struct.unpack("<H", data[6:8])[0] & 0x3FFF
                    height = struct.unpack("<H", data[8:10])[0] & 0x3FFF
                    return width, height
                if chunk_type == b"VP8L" and len(data) >= 5:
                    bits = int.from_bytes(data[1:5], "little")
                    width = (bits & 0x3FFF) + 1
                    height = ((bits >> 14) & 0x3FFF) + 1
                    return width, height
    except Exception as exc:
        log_message(f"dimension read failed: {file_path.name}; {exc}")
    return None


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
        max_side = 4096

    dimensions = get_image_dimensions(image_path)
    if upload_mode == "smart":
        if not dimensions:
            log_message(f"smart upload original, dimension unknown: {image_path.name}")
            return str(image_path)
        width, height = dimensions
        if max(width, height) <= max_side:
            log_message(
                f"smart upload original: {image_path.name} "
                f"({width}x{height}, {image_path.stat().st_size / 1024 / 1024:.2f}MB)"
            )
            return str(image_path)
        upload_mode = "png" if suffix == ".png" else "jpg95"

    cache_dir = OUTPUT_DIR / "upload-cache"
    cache_dir.mkdir(parents=True, exist_ok=True)
    if upload_mode == "png":
        out_path = cache_dir / f"{safe_stem(image_path)}-{max_side}.png"
        output_format = "png"
        quality = "100"
    else:
        out_path = cache_dir / f"{safe_stem(image_path)}-{max_side}-q95.jpg"
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


def save_result(response, prefix, timeout, output_dir=None, output_stem=None):
    target_dir = Path(output_dir) if output_dir else OUTPUT_DIR
    target_dir.mkdir(parents=True, exist_ok=True)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    name_stem = safe_stem(output_stem) if output_stem else f"{prefix}-{stamp}"

    if isinstance(response, dict) and response.get("raw_bytes") is not None:
        suffix = ".png"
        content_type = response.get("content_type", "")
        if "jpeg" in content_type or "jpg" in content_type:
            suffix = ".jpg"
        elif "webp" in content_type:
            suffix = ".webp"
        out = unique_path(target_dir / f"{name_stem}{suffix}")
        out.write_bytes(response["raw_bytes"])
        return out

    data = response.get("data") if isinstance(response, dict) else None
    if not data:
        raise RuntimeError("API response did not include a data array.")

    item = data[0]
    if item.get("b64_json"):
        image_bytes = base64.b64decode(item["b64_json"])
        out = unique_path(target_dir / f"{name_stem}.png")
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
        out = unique_path(target_dir / f"{name_stem}{suffix}")
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
        self.folder_path = StringVar()
        self.mask_path = StringVar()
        self.size = StringVar(value="1024x1024")
        self.response_format = StringVar(value="b64_json")
        self.output_path = StringVar()
        self.key_status = StringVar(value="已从本地读取" if self.api_key.get() else "未保存")
        self.image_info = StringVar(value="未选择图片")
        self.folder_info = StringVar(value="未选择文件夹")
        self.current_info = StringVar(value="当前处理：无")
        self.output_info = StringVar(value="输出图片：无")
        self.use_mask = BooleanVar(value=False)
        self.auto_resize = BooleanVar(value=True)
        self.remember_key = BooleanVar(value=True)
        self.max_side = StringVar(value="4096")
        self.upload_mode = StringVar(value="智能高质量")
        self.quality_mode = StringVar(value="标准")
        self.timeout_seconds = StringVar(value="300")
        self.busy = False
        self.pause_event = threading.Event()
        self.pause_event.set()

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

        ttk.Label(file_frame, text="文件夹").grid(row=3, column=0, sticky="w", pady=6)
        ttk.Entry(file_frame, textvariable=self.folder_path).grid(
            row=3, column=1, sticky="ew", padx=8, pady=6
        )
        ttk.Button(file_frame, text="选择文件夹", command=self.pick_folder).grid(
            row=3, column=2, padx=4, pady=6
        )
        ttk.Label(file_frame, textvariable=self.folder_info, style="Hint.TLabel").grid(
            row=4, column=1, sticky="w", padx=8, pady=(0, 4)
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
            values=["智能高质量", "高质JPG", "无损PNG", "原图"],
            width=12,
            state="readonly",
        ).pack(side="left", padx=(0, 10))
        ttk.Label(settings, text="长边").pack(side="left")
        ttk.Combobox(
            settings,
            textvariable=self.max_side,
            values=["2048", "3072", "4096"],
            width=8,
            state="readonly",
        ).pack(side="left", padx=8)
        ttk.Label(settings, text="质量").pack(side="left", padx=(12, 0))
        ttk.Combobox(
            settings,
            textvariable=self.quality_mode,
            values=["标准", "更稳", "精细"],
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
        self.batch_button = ttk.Button(action_row, text="批量处理文件夹", command=self.start_batch_edit)
        self.batch_button.pack(side="right", padx=(8, 0))
        self.edit_button = ttk.Button(action_row, text="开始编辑", command=self.start_edit)
        self.edit_button.pack(side="right")
        self.pause_button = ttk.Button(action_row, text="暂停", command=self.toggle_pause, state="disabled")
        self.pause_button.pack(side="right", padx=(8, 0))

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
        ttk.Label(output, textvariable=self.current_info, style="Hint.TLabel").grid(
            row=1, column=0, columnspan=4, sticky="w", padx=10, pady=(0, 4)
        )
        ttk.Label(output, textvariable=self.output_info, style="Hint.TLabel").grid(
            row=2, column=0, columnspan=4, sticky="w", padx=10, pady=(0, 10)
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

    def pick_folder(self):
        path = filedialog.askdirectory(title="选择要批量处理的图片文件夹")
        if path:
            self.folder_path.set(path)
            self.update_folder_info(path)

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
        self.image_info.set(describe_image_file(file_path))

    def update_folder_info(self, path):
        folder = Path(path)
        if not folder.exists():
            self.folder_info.set("文件夹不存在")
            return
        images = self.find_folder_images(folder)
        self.folder_info.set(f"找到 {len(images)} 张图片；输出到 stepfun_outputs 子文件夹")

    def find_folder_images(self, folder):
        return [
            path for path in sorted(Path(folder).iterdir(), key=lambda p: p.name.lower())
            if path.is_file() and path.suffix.lower() in SUPPORTED_IMAGE_EXTS
        ]

    def clear_prompt(self):
        self.edit_prompt.delete("1.0", "end")

    def toggle_pause(self):
        if self.pause_event.is_set():
            self.pause_event.clear()
            self.pause_button.configure(text="继续")
            self.status.configure(text="已请求暂停：当前图片完成后暂停")
        else:
            self.pause_event.set()
            self.pause_button.configure(text="暂停")
            self.status.configure(text="继续处理...")

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
        steps = normalize_quality_mode(self.quality_mode.get())
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
            "steps": steps,
            "timeout": timeout,
        }
        self.run_in_thread(lambda: self.edit_image(config))

    def start_batch_edit(self):
        if self.busy:
            return
        try:
            key = self.get_api_key()
        except ValueError as exc:
            messagebox.showwarning("缺少 API Key", str(exc))
            return
        self.maybe_save_api_key(key)

        prompt = self.edit_prompt.get("1.0", "end").strip()
        folder = self.folder_path.get().strip()
        if not folder:
            messagebox.showwarning("缺少文件夹", "请先选择要批量处理的图片文件夹。")
            return
        folder_path = Path(folder)
        if not folder_path.exists():
            messagebox.showwarning("文件夹不存在", "选择的文件夹不存在。")
            return
        if not prompt:
            messagebox.showwarning("缺少提示词", "请输入批量处理提示词。")
            return
        if len(prompt) > 512:
            messagebox.showwarning("提示词太长", "Step Image Edit 2 的提示词最多 512 个字符。")
            return

        images = self.find_folder_images(folder_path)
        if not images:
            messagebox.showinfo("没有图片", "该文件夹下没有 png/jpg/jpeg/webp 图片。")
            return

        upload_mode = normalize_upload_mode(self.upload_mode.get()) if self.auto_resize.get() else "original"
        output_dir = folder_path / "stepfun_outputs"
        config = {
            "api_key": key,
            "prompt": prompt,
            "images": images,
            "output_dir": output_dir,
            "response_format": self.response_format.get(),
            "upload_mode": upload_mode,
            "max_side": self.max_side.get(),
            "steps": normalize_quality_mode(self.quality_mode.get()),
            "timeout": int(self.timeout_seconds.get()),
        }
        self.run_in_thread(lambda: self.batch_edit_images(config), allow_pause=True)

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
            "steps": "8",
            "timeout": int(self.timeout_seconds.get()),
            "is_test": True,
        }
        self.run_in_thread(lambda: self.edit_image(config))

    def run_in_thread(self, target, allow_pause=False):
        self.busy = True
        self.pause_event.set()
        self.status.configure(text="请求中，请稍候...")
        self.edit_button.configure(state="disabled")
        self.batch_button.configure(state="disabled")
        self.pause_button.configure(state="normal" if allow_pause else "disabled", text="暂停")
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
        image_path = Path(config["image"])
        self.root.after(
            0,
            lambda p=image_path: self.current_info.set(f"当前处理：{describe_image_file(p)}"),
        )
        fields = {
            "model": MODEL_NAME,
            "prompt": config["prompt"],
            "response_format": config["response_format"],
            "cfg_scale": "1.0",
            "steps": config.get("steps", "8"),
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
        result_path = save_result(
            response,
            "edit",
            config.get("timeout", REQUEST_TIMEOUT),
            output_dir=config.get("output_dir"),
            output_stem=config.get("output_stem"),
        )
        self.root.after(
            0,
            lambda p=result_path: self.output_info.set(f"输出图片：{describe_image_file(p)}"),
        )
        return result_path

    def batch_edit_images(self, config):
        images = config["images"]
        output_dir = Path(config["output_dir"])
        output_dir.mkdir(parents=True, exist_ok=True)
        failures = []
        successes = []
        total = len(images)
        log_message(
            f"batch start: total={total}; output={output_dir}; "
            f"mode={config['upload_mode']}; max_side={config['max_side']}; steps={config['steps']}"
        )

        for index, image in enumerate(images, start=1):
            if not self.pause_event.is_set():
                self.root.after(
                    0,
                    lambda name=image.name: self.status.configure(
                        text=f"已暂停，下一张待处理：{name}"
                    ),
                )
            while not self.pause_event.is_set():
                time.sleep(0.2)

            self.root.after(
                0,
                lambda i=index, total=total, img=image: (
                    self.status.configure(text=f"批量处理中 {i}/{total}: {img.name}"),
                    self.current_info.set(f"当前处理：{describe_image_file(img)}"),
                ),
            )
            item_config = dict(config)
            item_config["image"] = str(image)
            item_config["mask"] = ""
            item_config["output_stem"] = f"{safe_stem(image)}-edited"
            try:
                result = self.edit_image(item_config)
                successes.append(result)
                log_message(f"batch success: {image.name} -> {result}")
            except Exception as exc:
                error = "".join(traceback.format_exception_only(type(exc), exc)).strip()
                failures.append((str(image), error))
                log_message(f"batch failed: {image.name}; {error}")

        failure_report = None
        if failures:
            stamp = time.strftime("%Y%m%d-%H%M%S")
            failure_report = unique_path(output_dir / f"batch-failed-{stamp}.txt")
            lines = [
                "Step Image Edit 2 批量处理失败清单",
                f"时间: {time.strftime('%Y-%m-%d %H:%M:%S')}",
                f"成功: {len(successes)}",
                f"失败: {len(failures)}",
                "",
            ]
            for image, error in failures:
                lines.append(image)
                lines.append(error)
                lines.append("")
            failure_report.write_text("\n".join(lines), encoding="utf-8")

        return {
            "type": "batch",
            "success": len(successes),
            "failed": len(failures),
            "total": total,
            "output_dir": output_dir,
            "failure_report": failure_report,
        }

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
        self.pause_event.set()
        self.edit_button.configure(state="normal")
        self.batch_button.configure(state="normal")
        self.pause_button.configure(state="disabled", text="暂停")
        self.progress.stop()
        if isinstance(result_path, dict) and result_path.get("type") == "batch":
            output_dir = result_path["output_dir"]
            self.output_path.set(str(output_dir))
            self.status.configure(
                text=f"批量完成：成功 {result_path['success']} / {result_path['total']}，失败 {result_path['failed']}"
            )
            if result_path["failed"]:
                messagebox.showwarning(
                    "批量处理完成，有失败图片",
                    "成功 {success} 张，失败 {failed} 张。\n失败清单：{report}".format(
                        success=result_path["success"],
                        failed=result_path["failed"],
                        report=result_path["failure_report"],
                    ),
                )
            else:
                messagebox.showinfo("批量处理完成", f"全部 {result_path['total']} 张图片处理成功。")
            try:
                os.startfile(output_dir)
            except OSError:
                pass
            return

        self.output_path.set(str(result_path))
        self.output_info.set(f"输出图片：{describe_image_file(result_path)}")
        self.status.configure(text="完成")
        try:
            os.startfile(result_path)
        except OSError:
            pass

    def finish_error(self, message):
        self.busy = False
        self.pause_event.set()
        self.edit_button.configure(state="normal")
        self.batch_button.configure(state="normal")
        self.pause_button.configure(state="disabled", text="暂停")
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
