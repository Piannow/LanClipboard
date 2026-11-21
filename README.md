# LanClipboard · 机顶盒剪切板

[![for AstrBotLite](https://img.shields.io/badge/for-AstrBotLite-7c5cff)](https://github.com/Piannow/AstrBotLite)
[![100% Web Coding](https://img.shields.io/badge/code-100%25%20Web%20Coding-7c5cff.svg)](README.md)
[![Android 4.4+](https://img.shields.io/badge/Android-4.4%2B-3ddc84.svg)](#兼容性)
[![MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

## 一句话

**在手机上复制文字，一键发到机顶盒剪切板，再粘贴进 AstrBotLite。**

机顶盒用遥控器逐字打字太折磨人，这工具就是来省这一步的。纯属懒。

## 100% Web Coding

代码、调试、构建、文档**全是 AI 对话生成的**，人就提了个需求。

## 怎么用

1. 手机、机顶盒连同一 WiFi
2. 两端都装[同一个 APK](https://github.com/Piannow/LanClipboard/releases/latest)
3. 机顶盒选「接收剪切板」并启动，手机选「发送剪切板」
4. 手机扫描到盒子 → 复制文字 → 点发送
5. 回机顶盒粘贴进 AstrBotLite

> 不想装手机端？浏览器打开 `http://机顶盒IP:9527` 也能发。

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