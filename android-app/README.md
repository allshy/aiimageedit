# Step Image Edit Android

这是当前桌面版工具的 Android 原生版本，使用 `step-image-edit-2` 的 `/v1/images/edits` 接口。

## 功能

- 保存 API Key 到手机本地 SharedPreferences
- 从相册/文件选择图片
- 支持单张编辑，也支持选择一个文件夹批量处理
- 批量处理时可以暂停/继续；暂停会在当前图片完成后停在下一张前
- 支持 `智能高质量`、`高质JPG`、`无损PNG`、`原图` 四种上传模式
- `智能高质量` 会自动识别 `9:16 / 2:3 / 3:4 / 1:1 / 4:3 / 3:2 / 16:9` 等常见比例，必要时补边到 16 倍数稳定尺寸，返回后再裁回原尺寸
- 支持长边 `1536 / 2048 / 3072 / 4096`
- 支持超时 `75 / 120 / 180 / 300 / 600` 秒
- 默认使用 `智能高质量`、长边 `4096`、超时 `300` 秒
- 显示当前处理图片与输出图片的格式、尺寸、文件大小
- 批量输出保存到所选文件夹下的 `stepfun_outputs`
- 批量失败时会在输出文件夹保存 `batch-failed-时间.txt`
- 单张结果保存到应用图片目录并显示预览

## 打开方式

用 Android Studio 打开 `android-app` 文件夹，等待 Gradle Sync 完成后运行 `app`。

当前电脑没有检测到 Android SDK / Gradle，所以我在本机无法直接打包 APK。

## 在线打包 APK

我已经在仓库根目录加入 GitHub Actions workflow：

```text
.github/workflows/build-android-apk.yml
```

使用方式：

1. 把整个文件夹上传到 GitHub 仓库。
2. 打开仓库的 `Actions` 页面。
3. 选择 `Build Android APK`。
4. 点击 `Run workflow`。
5. 构建完成后，在该次运行页面下载 `step-image-edit-debug-apk`。

下载到的 `app-debug.apk` 可以直接安装测试。首次安装可能需要在手机上允许“安装未知来源应用”。
