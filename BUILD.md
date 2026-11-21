# 构建说明

## 目录

- `sources/v1.1.2/MoreCheck/`、`sources/v1.1.50/MoreCheck/`、`sources/v1.1.70/MoreCheck/`、`sources/v1.1.90/MoreCheck/`：各版本的 Android Gradle 工程。
- `dist/`：已签名的发布包。
- `keystore.properties.example`：签名配置模板，需复制到各版本工程根目录并填入本机信息。

## 环境

- JDK 17
- Gradle 8.13（工程内已包含 wrapper）
- Android SDK：`platforms;android-36`、`build-tools;36.0.0` 或以上
- 应用最低支持 Android 10（API 29）

## 签名

出于安全考虑，发布密钥库**不随仓库提供**。请在本机自行生成，并妥善保管：

```bash
keytool -genkeypair -v \
  -keystore keystore/morecheck.jks \
  -alias morecheck \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=MoreCheck, OU=MoreCheck, O=MoreCheck, L=Beijing, ST=Beijing, C=CN"
```

然后为每个需要构建的版本准备签名配置：

```bash
cp keystore.properties.example sources/v1.1.70/MoreCheck/keystore.properties
```

编辑该文件，填入你的密钥库口令与密钥口令。`storeFile` 使用相对工程根目录的路径 `../../../keystore/morecheck.jks`。

> 已发布安装包使用同一个密钥库签名。若你生成自己的密钥库，构建出的包与仓库内的安装包签名不同，无法直接覆盖安装。

## 构建 release 包

```bash
export JAVA_HOME=<JDK17 路径>
export ANDROID_HOME=<Android SDK 路径>

gradle -p sources/v1.1.70/MoreCheck assembleRelease --console=plain
```

产物位于 `sources/v1.1.70/MoreCheck/app/build/outputs/apk/release/app-release.apk`。

如需构建其他版本，把上面的工程目录换成 `sources/v1.1.2/MoreCheck`、`sources/v1.1.50/MoreCheck` 或 `sources/v1.1.70/MoreCheck`。

## 说明

- 签名配置文件 `keystore.properties` 与 `*.jks` 已加入 `.gitignore`，不会被提交。
- 构建产物目录 `build/` 与 Gradle 缓存同样已忽略。
