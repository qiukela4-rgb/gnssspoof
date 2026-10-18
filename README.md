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

- JDK 17+（实测 21）
- Android SDK（compileSdk 35、build-tools 35）
- Gradle 8.9

```bash
./gradlew assembleRelease
```

产物：`app/build/outputs/apk/release/app-release-unsigned.apk`（需自行签名后安装）

## 免责声明

本项目**仅供学习与研究** LSPosed / Android 系统服务原理之用。

- 请勿用于作弊、绕过平台规则或任何违法违规用途
- 使用本模块产生的任何后果（账号封禁、数据异常等）由使用者自行承担
- 维护者不承担任何直接或间接责任

## License

GPL-3.0
