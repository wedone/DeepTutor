# DeepTutor Android 客户端

一个只做承载的 Android 壳：启动后全屏加载你自己部署的 DeepTutor Web 端，不内置任何 DeepTutor 运行时。

设计与实施细节见：

- [设计文档](../docs-for-dev/ANDROID_WEBVIEW_CLIENT.md)
- [实施计划](../docs-for-dev/ANDROID_WEBVIEW_CLIENT_PLAN.md)

## 本地构建

前置：JDK 21、Android SDK（`platforms;android-35`、`build-tools;35.0.0`）、Gradle 8.9。

```bash
cd apps/android
gradle wrapper --gradle-version 8.9   # 仅首次
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Windows 下可用 `gradlew.bat`。首次运行需要在 App 内输入服务器地址（例如 `https://your-domain:4433`）。

## 版本号

`versionName` 与 `versionCode` 直接来自仓库根目录的 `deeptutor/__version__.py`：

- `1.7.2` → `versionName=1.7.2`，`versionCode=10702`（`major*10000 + minor*100 + patch`）。

不要手动改 `app/build.gradle.kts` 里的版本，升级 DeepTutor 版本号即可。

## 发布

`.github/workflows/android-release.yml` 在 GitHub Release 发布时构建 APK 并上传到该 Release：

- 产物名：`DeepTutor-Android-v<version>.apk`
- 未配置签名 Secrets 时降级为 `DeepTutor-Android-v<version>-debug.apk`，并在日志中打印警告。

### 生成本地签名密钥

```bash
keytool -genkeypair -v -keystore release.keystore -alias deeptutor \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storetype PKCS12
```

### 配置仓库 Secrets

| Secret | 内容 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | `base64 -w0 release.keystore` 的输出 |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 密码 |
| `ANDROID_KEY_ALIAS` | `deeptutor` |
| `ANDROID_KEY_PASSWORD` | key 密码 |

可选仓库变量 `DEEPTUTOR_ANDROID_DEFAULT_URL`：注入为首次启动的默认服务器地址；不配置时输入框为空。

私钥与密码不得提交到仓库。
