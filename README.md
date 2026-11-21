# LanClipboard · 局域网剪切板

[![AstrBotLite](https://img.shields.io/badge/for-AstrBotLite-7c5cff)](https://github.com/Piannow/AstrBotLite)
[![Android](https://img.shields.io/badge/Android-4.4%2B-3ddc84.svg)](#兼容性)
[![License](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

**为 [AstrBotLite](https://github.com/Piannow/AstrBotLite) 编写的 Android 机顶盒辅助工具。**

在机顶盒上用遥控器填写 AstrBotLite 的人设卡、提示词等长文本十分麻烦。LanClipboard 让你可以在手机上复制或输入文字，通过局域网发送到机顶盒剪切板，再粘贴到 AstrBotLite，省去在电视上逐字输入的过程。

本工具独立于 AstrBotLite，不修改其配置，也不是 AstrBot 官方组件。除 AstrBotLite 外，也可用于其他需要向 Android 设备发送文本的场景。

## 下载

- [下载最新 APK](https://github.com/Piannow/LanClipboard/releases/latest)
- [查看 AstrBotLite 主项目](https://github.com/Piannow/AstrBotLite)

手机和机顶盒安装**同一个 APK**，启动后分别选择“手机端 · 发送剪切板”和“机顶盒 / 电视端 · 接收剪切板”。

## 快速使用

1. 让手机与机顶盒连接同一局域网，并关闭路由器的 AP/客户端隔离。
2. 在机顶盒打开 LanClipboard，选择“机顶盒 / 电视端 · 接收剪切板”，启动接收服务。
3. Android 6.0 及以上可开启“剪切板同步复制服务”无障碍权限；若系统不支持，可关闭“无障碍全选复制（兼容模式）”，使用直接写剪切板和固定文件。
4. 在手机打开 LanClipboard，选择“手机端 · 发送剪切板”，扫描并选中机顶盒；扫描不到时手动输入机顶盒 IP。
5. 输入或粘贴 AstrBotLite 人设卡等文本，点击“发送文本”；需要连续同步时可开启“自动同步（复制即发送）”。
6. 回到机顶盒上的 AstrBotLite，在对应输入框中粘贴文字。
7. 也可以不安装手机端，直接用手机浏览器打开 `http://机顶盒IP:9527` 发送文字。

## 功能

- 手机复制文字，机顶盒接收后写入系统剪切板。
- 自动同步、手动发送和浏览器发送三种使用方式。
- UDP 自动发现接收端，支持手动输入 IP。
- 保留固定文件 `剪切板.txt`，每次覆盖写入，不堆叠历史文本。
- 普通 Android 与 Android TV 双入口，支持遥控器焦点操作。
- 前台接收服务与开机恢复；具体后台行为取决于厂商系统限制。

## 兼容性

- 最低 Android 4.4（API 19），`targetSdk 28`。
- 支持 Android 4.4～5.1 运营商定制 IPTV 盒子，例如华为 EC6108、中兴 B860AV、创维 E900 等同系统版本设备。
- 纯有线网络、无触摸屏设备可安装；Wi-Fi、以太网、触摸屏和 Leanback 均声明为非必需能力。
- Android 6.0 及以上提供无障碍全选复制兼容模式；旧系统使用直接写剪切板方案。

**以上是源码与安装包层面的兼容适配，不表示已在列举的具体型号上实测。** 某些厂商系统可能限制后台服务、剪切板或自启动；遇到问题时请在系统的后台运行/自启动管理中允许本应用。

详细说明见 [兼容版使用说明](docs/兼容版使用说明.md)。

## 从源码构建

环境：JDK 17、Android SDK Platform 34、Gradle 8.7。

本项目不附带 Android Gradle Wrapper，请使用本机 Gradle 8.7：

```bash
gradle assembleRelease
```

工程未使用第三方运行时依赖。构建产物位于 `app/build/outputs/apk/release/`。

### 签名

**签名密钥不属于公开源码，本仓库不会提供 `lanclip.jks` 或密码。** 请创建自己的密钥，并在项目根目录的 `keystore.properties` 中配置：

```properties
storeFile=/你的绝对路径/lanclip.jks
storePassword=你的密钥库密码
keyAlias=你的别名
keyPassword=你的密钥密码
```

未配置签名时可以构建 Debug APK：

```bash
gradle assembleDebug
```

自己的签名与 Release 页面中的官方签名不同，覆盖安装前需要卸载其他签名版本并备份数据。

## 隐私与安全

- 文本仅通过你所在局域网的 HTTP 明文协议发送，**没有加密和身份验证**。
- 不要在不可信的公共网络上发送人设卡中的 API 密钥、Token、密码或其他敏感信息。
- HTTP 服务端口为 `9527`，UDP 发现端口为 `9528`。
- 无障碍服务仅在本应用透明窗口内执行全选与复制，不读取其他应用内容。
- 本项目与腾讯、QQ、AstrBot 官方无隶属关系。

## 许可证

[MIT License](LICENSE)。欢迎提交 Issue 和 Pull Request，反馈机顶盒型号、Android 版本与实际测试结果。
