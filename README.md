# GnssSpoof

一个 LSPosed 模块：**伪造 GNSS 卫星状态（GPS 信号 / 卫星数量）**，让定位类 App（如校园跑步软件）显示正常的 GPS 卫星数，即使系统正在使用虚拟定位。

## 背景问题

- 使用虚拟定位（影梭系 / mock location 类工具）模拟位置时，系统会把真实 GNSS 引擎的卫星上报停掉
- 结果：位置是假的，但「GPS 信号 / 卫星数量」恒为 0
- 很多跑步软件会据此判定成绩无效，甚至触发风控

## 解决方案

注入 `system_server`（系统框架进程），三层拦截同时生效：

1. **分发替换** —— Hook `GnssStatusProvider.onReportSvStatus`，把真实卫星表换成伪造的
2. **序列化拦截** —— Hook `GnssStatus.writeToParcel`，任何跨进程传输的卫星数据强制重写（主力通道，绕开厂商系统差异）
3. **主动推送** —— Hook `GnssStatusProvider.addListener`，App 注册卫星回调时立即主动推一份伪造数据（虚拟定位停掉 GNSS 上报时的救命通道）

全程**不 Hook 目标 App 进程**：对带「检测到被注入即闪退」反制的 App 友好，作用域只需要系统框架。

默认伪造 **8 颗 GPS 卫星**（信噪比 32~38 dB-Hz、仰角 45°、方位角均匀分布），贴近真实室外水平，不易引起注意。

## 环境要求

- Android 12+（在 Android 15 / 小米澎湃 OS 3.0.1.0 实机验证）
- Root：Magisk / Kitsune 等，且 **Zygisk 已启用**
- LSPosed（Zygisk 版）

## 使用方法

1. 从 Release 下载 APK 并安装
2. LSPosed → 模块 → 启用 **GnssSpoof**
3. 作用域：**只勾「系统框架」**（⚠️ 不要勾任何 App，否则可能被反注入检测）
4. 重启手机

之后任何通过标准 API 读取 GNSS 卫星状态的 App 都会看到伪造的卫星数。

## 修改卫星数量

编辑 `app/src/main/java/com/jiuhu/gnssspoof/MainHook.java`：

```java
private static final int FAKE_SV_COUNT = 8;  // 改成你想要的颗数
```

重新构建即可。

## 构建

环境要求：

- JDK 17+（实测 21）
- Android SDK（compileSdk 35、build-tools 35）
- Gradle 8.9（wrapper 已锁定）

统一使用项目根目录的 `build.cmd`，不要直接调用 `gradlew`。脚本已写死 `JAVA_HOME=D:\tools\jdk-21` 与 `GRADLE_USER_HOME=D:\tools\gradle\home`，无需手动设置环境变量。

| 命令 | 作用 | 产物 |
|---|---|---|
| `build.cmd` | 构建 debug | `app/build/outputs/apk/debug/app-debug.apk` |
| `build.cmd release` | 构建 release | `app/build/outputs/apk/release/app-release-unsigned.apk`（未配置签名时） |
| `build.cmd test` | 运行单元测试 | `app/build/test-results/` |
| `build.cmd clean` | 清理构建产物 | — |

release 签名：在项目根放置 `keystore.properties`（已被 `.gitignore` 忽略），包含 `storeFile` / `storePassword` / `keyAlias` / `keyPassword` 四个字段。文件不存在时 release 产物保持 unsigned，属正常结果。

## 诊断

模块在 `system_server` 中写入 `debug.gnssspoof.*` 自检属性。用下面这一条命令一次性读取全部属性：

```bash
adb shell getprop | grep gnssspoof
```

| 属性名 | 含义 | 正常值 | 读取命令 |
|---|---|---|---|
| `debug.gnssspoof.injected` | 模块成功注入 `system_server` | `1` | `adb shell getprop \| grep gnssspoof` |
| `debug.gnssspoof.const_ok` | 从 `GnssStatus` 反射读取编码常量成功，值为 `SV_SHIFT/CONST_SHIFT/FLAGS_FULL` | `12/8/15`（Android 12+ 实测） | `adb shell getprop \| grep gnssspoof` |
| `debug.gnssspoof.const_err` | 反射读取编码常量失败时的异常摘要 | 空（无错误时不存在） | `adb shell getprop \| grep gnssspoof` |
| `debug.gnssspoof.provider_err` | 查找 `GnssStatusProvider` 失败时的异常摘要 | 空（无错误时不存在） | `adb shell getprop \| grep gnssspoof` |
| `debug.gnssspoof.replaced` | 分发替换通道最近一次生效的毫秒时间戳 | 毫秒时间戳，非 0 | `adb shell getprop \| grep gnssspoof` |
| `debug.gnssspoof.hooked1` | `onReportSvStatus` 钩子挂载成功 | `1` | `adb shell getprop \| grep gnssspoof` |
| `debug.gnssspoof.hookerr1` | `onReportSvStatus` 钩子挂载失败时的异常摘要 | 空（无错误时不存在） | `adb shell getprop \| grep gnssspoof` |
| `debug.gnssspoof.push` | 主动推送通道最近一次向监听者推送的毫秒时间戳 | 毫秒时间戳，非 0 | `adb shell getprop \| grep gnssspoof` |
| `debug.gnssspoof.hooked3` | `addListener` 钩子挂载成功 | `1` | `adb shell getprop \| grep gnssspoof` |
| `debug.gnssspoof.hookerr3` | `addListener` 钩子挂载失败时的异常摘要 | 空（无错误时不存在） | `adb shell getprop \| grep gnssspoof` |
| `debug.gnssspoof.hooked2` | `writeToParcel` 钩子挂载成功 | `1` | `adb shell getprop \| grep gnssspoof` |
| `debug.gnssspoof.hookerr2` | `writeToParcel` 钩子挂载失败时的异常摘要 | 空（无错误时不存在） | `adb shell getprop \| grep gnssspoof` |
| `debug.gnssspoof.w2p` | 序列化拦截通道最近一次生效的毫秒时间戳 | 毫秒时间戳，非 0 | `adb shell getprop \| grep gnssspoof` |

`*_err` 属性只在对应异常发生时出现；正常设备上不应看到 `const_err` / `provider_err` / `hookerr1` / `hookerr2` / `hookerr3`。

## 免责声明

本项目**仅供学习与研究** LSPosed / Android 系统服务原理之用。

- 请勿用于作弊、绕过平台规则或任何违法违规用途
- 使用本模块产生的任何后果（账号封禁、数据异常等）由使用者自行承担
- 维护者不承担任何直接或间接责任

## License

GPL-3.0
