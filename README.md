# Step Image Edit 2 本地图形界面

这是一个零第三方依赖的 Windows 本地 GUI，用 Python 标准库调用 StepFun API。

## 使用方法

1. 双击 `start_step_image_edit_gui.bat`。
2. 在界面里填写你的 StepFun API Key。默认勾选“自动保存”，第一次请求或点击“保存”后，下次打开会自动读取。

也可以先在 PowerShell 设置环境变量：

```powershell
$env:STEPFUN_API_KEY="你的API Key"
python step_image_edit_gui.py
```

程序自动保存时会写入当前文件夹的 `.env`，格式如下：

```text
STEPFUN_API_KEY=你的API Key
```

3. 选择原图，输入提示词，点击“开始编辑”。
4. 生成结果会保存到 `outputs` 文件夹，并自动尝试打开。

也可以选择一个文件夹，输入同一个提示词后点击“批量处理文件夹”。程序会处理该文件夹下的 `png/jpg/jpeg/webp` 图片，并把结果保存到该文件夹内的 `stepfun_outputs` 子文件夹。

批量处理时可以点击“暂停”，程序会在当前图片处理完成后停在下一张前；点击“继续”后恢复处理。

如果你更新了程序，请先关闭旧窗口，再重新双击 `start_step_image_edit_gui.bat`。

界面右上角有“测试 API”按钮，会用程序生成的小图发起一次最小编辑请求。建议先点它：

- 测试 API 成功：说明 Key 和网络没问题，超时多半和你选择的原图大小、格式或文件路径有关。
- 测试 API 也超时：说明是网络代理、接口访问或账号侧问题。

## 接口

- 图片编辑：`POST https://api.stepfun.com/v1/images/edits`
- 模型：`step-image-edit-2`

API Key 只会用于本机请求头：

```text
Authorization: Bearer 你的API Key
```

不要把 API Key 发给别人，也不要提交到公开仓库。

## 排错

- 程序请求超过界面里选择的超时时间会自动停止并弹出错误。
- 错误日志保存在 `step_image_edit_gui.log`。
- 日志会记录代理、上传体大小和图片大小，不会记录 API Key。
- `step-image-edit-2` 编辑接口会按输入图尺寸返回，`size` 对编辑结果不生效。
- 图片编辑页默认开启“预处理上传”，默认使用 `智能高质量`、长边 4096，原图不会被修改。
- 上传模式可选：
  - `智能高质量`：如果图片长边不超过 4096，直接原图上传；超过 4096 时才预处理到 4096。
  - `高质JPG`：长边缩放后用 JPG 质量 95，体积较小。
  - `无损PNG`：长边缩放后保存为 PNG，不再做 JPG 有损压缩；但只要缩放尺寸，细节仍会变化。
  - `原图`：不缩放、不转格式，画质最完整，但更容易上传慢或超时。
- 质量模式可选 `标准 / 更稳 / 精细`，分别对应 `steps=8 / 12 / 16`。默认 `标准` 使用官方示例值，速度和质量最均衡。
- 超时时间可在界面选择 `75` 到 `600` 秒。批量处理或原图上传建议用 `300` 秒。
- 批量处理如果有失败图片，会弹窗提示，并在输出文件夹写入 `batch-failed-时间.txt` 失败清单。
- 输出区域会显示当前处理图片和最近输出图片的格式、尺寸、文件大小。
- 如果不想保存 Key，取消勾选“自动保存”，或点击“清除”删除本地 `.env`。

## Android 版本

Android 原生工程在 `android-app` 文件夹。用 Android Studio 打开该文件夹，Gradle Sync 完成后运行 `app` 即可。

Android 版已同步单张编辑、批量文件夹处理、暂停/继续、当前/输出图片信息显示、智能高质量上传、自动比例适配、超时设置和失败清单。

当前电脑没有检测到 Android SDK / Gradle，所以本机暂时无法直接打包 APK。

如果不想安装 Android 环境，可以把整个文件夹上传到 GitHub，然后在 `Actions` 里运行 `Build Android APK`，云端会打包并提供 `app-debug.apk` 下载。
