# 签名文件说明 · lanclip.jks

## 这是什么

这是 LanClipboard 的 **Android 应用签名密钥库**。

把这个文件放进项目根目录、配好 `keystore.properties`，用 `gradle assembleRelease` 构建出来的 APK，
**签名与 GitHub Release 里发布的 APK 完全相同**，因此可以直接覆盖安装，不需要先卸载旧版。

## 文件与凭据

| 项目 | 值 |
|---|---|
| 文件名 | `lanclip.jks` |
| 格式 | JKS（Java KeyStore） |
| 别名 alias | `lanclip` |
| 库密码 storePassword | `lanclip123` |
| 密钥密码 keyPassword | `lanclip123` |
| 密钥算法 | RSA |
| 密钥长度 | 2048 位 |
| 证书签名算法 | SHA256withRSA |
| 证书主体 | `CN=LanClipboard, O=Personal, C=CN` |
| 证书 SHA-256 | `C9:DA:EC:E6:33:03:6C:DE:63:78:2E:42:89:51:67:28:CD:50:7D:16:2D:05:3C:63:25:13:8F:2B:F4:ED:31:0E` |
| 证书 SHA-1 | `21:7E:E9:9E:96:ED:9B:39:AD:1A:2C:C6:51:3B:CC:66:7B:F2:AA:30` |

## 怎么用

### 1. 放置文件

把 `lanclip.jks` 放到项目根目录（与 `settings.gradle` 同级）。

### 2. 创建 keystore.properties

在项目根目录创建 `keystore.properties`：

```properties
storeFile=lanclip.jks
storePassword=lanclip123
keyAlias=lanclip
keyPassword=lanclip123
```

> 注意：`storeFile` 使用相对路径时，是相对于 **app 模块目录**（因为 `app/build.gradle` 里写的是
> `file("../lanclip.jks")` 这种形式），所以最稳妥的写法是用绝对路径：
>
> ```properties
> storeFile=/absolute/path/to/lanclip.jks
> storePassword=lanclip123
> keyAlias=lanclip
> keyPassword=lanclip123
> ```

### 3. 构建

```bash
gradle assembleRelease
```

产物：`app/build/outputs/apk/release/局域网剪切板-v2.0-release.apk`

### 4. 验证签名

```bash
apksigner verify --print-certs app/build/outputs/apk/release/局域网剪切板-v2.0-release.apk
```

输出的 SHA-256 应该等于上面表格里的值。如果一致，说明签对了。

## 覆盖安装的前提

新旧 APK 必须满足：

1. **包名相同**：`com.lanclip.sync`
2. **签名证书相同**：SHA-256 一致（用这个 jks 构建即可）
3. **versionCode 不小于已装版本**：覆盖安装要求新包 versionCode ≥ 旧包

`app/build.gradle` 里的 `versionCode` 每次发版都会递增，当前是 37。

如果覆盖安装仍然失败，说明已安装的包不是用这个 jks 签的（例如从别人那里拿到的改包），此时只能卸载重装。
**卸载会清除应用数据**（包括接收计数、上次接收内容、连接过的盒子地址等），但不会影响 `剪切板.txt`（它在应用私有目录，会随卸载一起删除）。

## 安全提醒

**这个密钥库和密码是公开的。**

公开的目的很明确：让任何人（包括你自己在另一台机器上）都能构建出可以覆盖安装的包。

代价是：**任何知道这组凭据的人，都能签出"看起来来自本作者"的 APK**。

因此：

- 这个 APK **不能作为信任凭据**。不要因为"签名一样"就认为安装包一定安全。
- 如果你要把它分发给别人，请自己生成一个新的 keystore，不要用这个。
- 如果你要发布到任何应用商店，请自己生成一个新的 keystore，并且**永远不要公开**。
- 这个 jks 只适用于"局域网内自己用"这个场景。

## 自己生成新密钥（推荐做法）

如果你不想用公开密钥，可以自己生成：

```bash
keytool -genkeypair -v \
  -keystore my-release.jks \
  -alias myalias \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -storepass 你的密码 \
  -keypass 你的密码 \
  -dname "CN=YourName, O=Personal, C=CN"
```

然后把 `keystore.properties` 指向新文件。

**注意：用新密钥签出来的包，与 Release 里的 APK 签名不同，不能覆盖安装，必须先卸载旧版。**
