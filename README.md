# LanClipboard · 机顶盒剪切板

[![for AstrBotLite](https://img.shields.io/badge/for-AstrBotLite-7c5cff)](https://github.com/Piannow/AstrBotLite)
[![100% Web Coding](https://img.shields.io/badge/code-100%25%20Web%20Coding-7c5cff.svg)](README.md)
[![Android 4.4+](https://img.shields.io/badge/Android-4.4%2B-3ddc84.svg)](#兼容性)
[![MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

## 一句话

**在手机上复制文字，回软件点一下「推送」，机顶盒剪切板就有内容了，再粘贴进 AstrBotLite。**

机顶盒用遥控器逐字打字太折磨人，这工具就是来省这一步的。纯属懒。

## 100% Web Coding

代码、调试、构建、文档**全是 AI 对话生成的**，人就提了个需求。

## 怎么用

前置条件：手机和机顶盒都装了[同一个 APK](https://github.com/Piannow/LanClipboard/releases/latest)，机顶盒开启「接收剪切板」服务 + 无障碍权限。

1. 手机上复制一段文字
2. 回到 LanClipboard，点「推送」
3. 搞定，机顶盒剪切板里就是那段文字了
4. 回 AstrBotLite 粘贴即可

> 想更省事？开启「自动同步」，复制即推送，连点都不用点。

> 不想装手机端？浏览器打开 `http://机顶盒IP:9527` 也能发。

## 更新中心（v1.2 新增）

App 内带「更新中心」，两个按钮：

- **检测剪切板更新**：从 GitHub 检查 LanClipboard 新版本 → 有新版下载 APK → 点安装 → 申请"安装未知应用"权限 → 装到本机。
- **检测 AstrBotLite 更新**：从 GitHub 检查并下载 AstrBotLite APK → 同样安装。
- **远程安装**：扫描局域网开放 5555（无线 adb）端口的安卓机 → 直连 → 检测已装则跳过，否则把剪切板 APK 通过 ADB 静默装到盒子上。

> 远程安装的前提：盒子已开启「无线调试」(5555 端口)，且首次需在盒子上允许 adb 授权（多数盒子默认放行）。
> 自更新/安装需要 Android 8.0+ 时在系统设置里允许本应用「安装未知应用」。

## 兼容性

- Android 4.4 起
- 老 IPTV 盒子（华为 EC6108 / 中兴 B860AV / 创维 E900 之类）
- 纯有线、无触摸屏都能装
- 后台常驻受厂商系统影响，被杀就在系统里允许自启动

## 构建

```bash
gradle assembleRelease   # 需 JDK17 + Android SDK34 + Gradle8.7
```

签名密钥不公开，自己配 `keystore.properties`。

## 安全

明文局域网传输，**没加密**，别在公共 WiFi 上发密钥/密码。

---

[MIT](LICENSE) · 由 [AstrBotLite](https://github.com/Piannow/AstrBotLite) 衍生，非官方组件。