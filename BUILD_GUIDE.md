# FluxM3U8 编译完全指南（零基础版）

这份文档假设你**从没编译过安卓应用**。两条路都能出 APK，按顺序照做即可。

***

## 零、先选哪条路

| <br /> | 云编译（GitHub Actions） | 本地编译（Android Studio）                 |
| ------ | ------------------- | ------------------------------------ |
| 要装什么   | **什么都不用装**，有浏览器就行   | Android Studio + Android SDK（约 5 GB） |
| 耗时     | 第一次约 10\~15 分钟      | 装环境 30\~60 分钟，之后每次 2\~5 分钟           |
| 电脑要求   | 无（手机都能操作）           | 内存 8 GB 以上，硬盘留 15 GB                 |
| 网络     | 需要能打开 GitHub        | 需要能下载 Google 的依赖                     |
| 改代码方便吗 | 改完要重新上传，麻烦          | **改完立刻能跑，最方便**                       |
| 适合谁    | 只想赶紧拿到 APK 用        | 想改代码、长期折腾                            |

**我的建议：**

- 只想装到手机上用 → 走**云编译**（第一节），最快最省事
- 想改界面、调参数、长期玩 → 装 **Android Studio**（第二节），一劳永逸
- 拿不准 → 先云编译拿到 APK 确认能用，有兴致了再装本地环境

***

# 第一节 · 云编译（不用装任何软件）

整体流程就五步：

```
注册 GitHub  →  新建仓库  →  上传代码  →  自动构建  →  下载 APK
```

## 1.1 注册 GitHub 账号

1. 浏览器打开 `https://github.com`
2. 右上角点 **Sign up**（注册）
3. 填邮箱 → 密码 → 用户名（用户名会是你的仓库地址一部分，用字母数字就行）
4. 按提示完成邮箱验证（**没验证邮箱后面建不了仓库**）
5. 中间会问"你是团队还是个人"、打算用来干嘛，随便选，不影响

> 国内访问 GitHub 偶尔会慢或打不开，多刷新几次，或换个时间段（凌晨通常最顺）。

## 1.2 新建一个仓库

1. 登录后，右上角点 **+** → **New repository**
2. 按下图填：

| 项目                              | 填什么                                      |
| ------------------------------- | ---------------------------------------- |
| Repository name（仓库名）            | `FluxM3U8`                               |
| Description（描述）                 | 随便写，比如 `m3u8 downloader`，也可以留空           |
| 公开还是私有                          | 建议选 **Private（私有）** ← 下面解释               |
| Initialize this repository with | **全部不要勾**（README、.gitignore、license 都别勾） |

1. 点绿色按钮 **Create repository**

**关于私不私有：**

- **Private（私有）**：只有你自己看得见，免费额度是每月 2000 分钟构建时间，
  一次构建约 5\~10 分钟，够你构建上百次。**推荐选这个。**
- **Public（公开）**：任何人都能看到源码，构建时间无限。

> ⚠️ 千万不要把签名密钥文件（`.jks`）传上去，本项目 `.gitignore` 已经帮你排除了。

## 1.3 上传代码（两种办法，选一种）

### 办法 A：网页拖拽（推荐，不用装任何东西）

1. 先在你电脑上把 `FluxM3U8-Android.zip` **解压**
   - Windows：右键压缩包 → **全部解压缩**
   - Mac：双击 zip 即可
   - 解压后得到一个 `FluxM3U8-Android` 文件夹
2. 打开那个文件夹，确认里面**直接**是这些内容（不要再多一层文件夹）：
   ```
   FluxM3U8-Android/
   ├── .github/          ← 注意这个点开头的文件夹，很重要
   ├── app/
   ├── gradle/
   ├── build.gradle.kts
   ├── settings.gradle.kts
   ├── gradle.properties
   ├── .gitignore
   ├── README.md
   └── BUILD_GUIDE.md
   ```
3. 回到 GitHub 刚建好的空仓库页面，你会看到中间有一行浅蓝色小字：
   **"uploading an existing file"** → 点它

   （如果没看到，也可以直接看下一步）
4. 把 `FluxM3U8-Android` 文件夹**里的全部内容**用鼠标拖到网页虚线框里
   - 注意：是拖**文件夹里面的内容**，不是拖文件夹本身，也不是拖 zip 包
   - 一次可以拖多个，全都选中拖进去
5. 页面下方会出现文件列表，滚动到底部，点绿色按钮 **Commit changes**
   - 弹窗里直接再点一次 **Commit changes** 确认
6. 等几秒，页面刷新后就能看到文件了

### 办法 B：用 Git 命令行（你有 Git 的话更快）

```bash
cd FluxM3U8-Android
git init
git add .
git commit -m "first commit"
git branch -M main
git remote add origin https://github.com/你的用户名/FluxM3U8.git
git push -u origin main
```

推的时候会让你输账号密码。注意：GitHub 从 2021 年起**不再接受账号密码**，
要用 Personal Access Token：

> GitHub 右上角头像 → **Settings** → 最下方 **Developer settings**
> → **Personal access tokens** → **Tokens (classic)** → **Generate new token (classic)**
> → 勾选 `repo` → 生成 → **复制保存好**（关掉页面就再也看不到了）
> → 推代码时密码栏粘贴这个 token

## 1.4 ⚠️ 关键：确认 `.github` 上传成功

这一步**非常多人踩坑**。`.github` 是点开头的隐藏文件夹，
有些系统/浏览器的拖拽上传会把它悄悄跳过。没有它，网页上就不会出现 Actions，也就不会自动构建。

**检查方法：**

在仓库文件列表里，看有没有 `.github` 这个文件夹。
如果看不到，或者点进去没有 `workflows/build-apk.yml`，就手动补一个：

1. 仓库页面点 **Add file** → **Create new file**
2. 上方文件名框里**完整输入**（GitHub 会自动建目录）：
   ```
   .github/workflows/build-apk.yml
   ```
3. 内容框里粘贴下面这段（这是完整的构建配置）：

<details>
<summary>点这里展开完整配置（点击后复制）</summary>

```yaml
name: 构建 APK

# 触发条件：
#  1. 推送代码到 main / master 分支时自动构建
#  2. 在 GitHub 网页上手动点「Run workflow」也能构建
on:
  push:
    branches: [ main, master ]
  workflow_dispatch:
    inputs:
      build_type:
        description: '要构建的类型'
        required: true
        default: 'debug'
        type: choice
        options:
          - debug
          - release

jobs:
  build:
    runs-on: ubuntu-latest
    timeout-minutes: 40

    steps:
      # ── 1. 拉取代码 ──
      - name: 检出代码
        uses: actions/checkout@v4

      # ── 2. 安装 JDK 17 ──
      # 本项目 compileSdk 34 + AGP 8.1.4，必须 JDK 17，低了会报
      # "Unsupported class file major version"
      - name: 安装 JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'

      # ── 3. 安装 Android SDK ──
      - name: 安装 Android SDK
        uses: android-actions/setup-android@v3

      - name: 接受 SDK 许可协议
        run: |
          yes | sdkmanager --licenses > /dev/null || true
          sdkmanager "platforms;android-34" "build-tools;34.0.0" "platform-tools" > /dev/null || true

      # ── 4. 缓存依赖 ──
      # 第一次构建要下载 500MB+ 依赖，缓存后第二次只要 1~2 分钟
      - name: 缓存 Gradle 依赖
        uses: actions/cache@v4
        with:
          path: |
            ~/.gradle/caches
            ~/.gradle/wrapper
          key: ${{ runner.os }}-gradle-${{ hashFiles('**/*.gradle*', '**/gradle-wrapper.properties') }}
          restore-keys: |
            ${{ runner.os }}-gradle-

      # ── 5. 安装 Gradle 8.2 ──
      # 项目里没有打包 gradle-wrapper.jar，所以这里直接装一个，
      # 版本必须和 gradle/wrapper/gradle-wrapper.properties 里写的保持一致
      - name: 安装 Gradle 8.2
        run: |
          wget -q https://services.gradle.org/distributions/gradle-8.2-bin.zip
          unzip -q gradle-8.2-bin.zip -d /opt/
          chmod +x /opt/gradle-8.2/bin/gradle
          echo "/opt/gradle-8.2/bin" >> $GITHUB_PATH

      - name: 校验 Gradle 版本
        run: gradle --version

      # ── 6. 构建 ──
      - name: 构建 Debug APK
        if: ${{ github.event.inputs.build_type != 'release' }}
        run: gradle assembleDebug --no-daemon --stacktrace

      - name: 构建 Release APK
        if: ${{ github.event.inputs.build_type == 'release' }}
        run: gradle assembleRelease --no-daemon --stacktrace

      # ── 6.5 Release 签名 ──
      # 未签名的 APK 是装不上的，所以这里现场生成一个临时密钥把它签了。
      # 注意：这个密钥是每次构建临时生成的，仅够自用；
      # 要正式发布请用你自己的密钥在本地签名（见 BUILD_GUIDE.md 2.7）。
      - name: 生成临时签名密钥
        if: ${{ github.event.inputs.build_type == 'release' }}
        run: |
          keytool -genkeypair -v \
            -keystore flux-release.jks \
            -keyalg RSA -keysize 2048 -validity 10000 \
            -alias flux \
            -storepass flux1234 -keypass flux1234 \
            -dname "CN=Flux, OU=Flux, O=Flux, L=Unknown, ST=Unknown, C=CN"

      - name: 对齐并签名 Release APK
        if: ${{ github.event.inputs.build_type == 'release' }}
        run: |
          ZIPALIGN=$(find "$ANDROID_HOME"/build-tools -name zipalign -type f | head -1)
          APKSIGNER=$(find "$ANDROID_HOME"/build-tools -name apksigner -type f | head -1)
          echo "zipalign: $ZIPALIGN"
          echo "apksigner: $APKSIGNER"
          "$ZIPALIGN" -v -p 4 \
            app/build/outputs/apk/release/app-release-unsigned.apk \
            app-release-aligned.apk
          "$APKSIGNER" sign \
            --ks flux-release.jks --ks-key-alias flux \
            --ks-pass pass:flux1234 --key-pass pass:flux1234 \
            --out app-release-signed.apk \
            app-release-aligned.apk
          "$APKSIGNER" verify app-release-signed.apk
          echo "✅ 签名验证通过"

      # ── 7. 上传产物 ──
      # 构建完在 Actions 页面底部可以下载，保留 30 天
      - name: 上传 Debug APK
        if: ${{ github.event.inputs.build_type != 'release' }}
        uses: actions/upload-artifact@v4
        with:
          name: FluxM3U8-debug
          path: app/build/outputs/apk/debug/app-debug.apk
          retention-days: 30
          if-no-files-found: error

      - name: 上传 Release APK
        if: ${{ github.event.inputs.build_type == 'release' }}
        uses: actions/upload-artifact@v4
        with:
          name: FluxM3U8-release
          path: app-release-signed.apk
          retention-days: 30
          if-no-files-found: error

      # ── 8. 构建摘要 ──
      - name: 输出构建结果
        if: always()
        run: |
          echo "### 构建完成 ✅" >> $GITHUB_STEP_SUMMARY
          echo "" >> $GITHUB_STEP_SUMMARY
          echo "到本页面最下方的 **Artifacts** 区域下载 APK。" >> $GITHUB_STEP_SUMMARY
          echo "" >> $GITHUB_STEP_SUMMARY
          echo "> 下载后传到手机安装即可，需要允许「安装未知应用」。" >> $GITHUB_STEP_SUMMARY
```

</details>

1. 拉到最下面点 **Commit changes** → 再点一次确认

## 1.5 开始构建

上传完代码后**会自动开始构建**，你只要等。

看进度：点仓库页面顶部的 **Actions** 标签页

```
你的仓库  >  <> Code   ! Issues   ⚙ Settings
             ─────────
             ✓ Actions      ← 点这个
```

进去后你会看到一个正在跑的任务，图标含义：

| 图标        | 含义           |
| --------- | ------------ |
| 🟡 黄色圆点在转 | 正在构建，**耐心等** |
| ✅ 绿色对勾    | 成功，可以下载了     |
| ❌ 红色叉号    | 失败了，看第三节排查   |

**第一次构建约 10\~15 分钟**（要下载 Android SDK + 全套依赖），
之后有了缓存只要 **1\~3 分钟**。

> 上传完如果 Actions 里是空的，多半是 1.4 那步没做对，回去检查一下。
> 也可以在 Actions 页面左侧点「构建 APK」→ 右上角 **Run workflow** → 绿色按钮手动触发。
> 手动触发时可以选 `debug` 或 `release`：
>
> - **debug**（默认）：功能完整，直接能装，日常用选这个
> - **release**：会自动生成一个临时密钥签名后打包，体积略小；
>   但密钥是每次临时的，**正式发布请用自己的密钥在本地签**（见 2.7）

## 1.6 下载 APK

构建成功（绿色 ✅）之后：

1. 点进那条成功的构建记录
2. 页面往下拉，找到 **Artifacts** 区域
3. 点 **FluxM3U8-debug**（或 release），浏览器会自动下载一个 **zip 包**
4. 解压这个 zip，里面就是 `app-debug.apk`

> Artifacts 默认保留 **30 天**，过期会自动删除，需要的话重新跑一次。

## 1.7 装到手机上

把 APK 弄到手机上有几种办法，任选：

- **微信/QQ 传给自己**：文件传输助手发到手机，点开安装
- **数据线**：电脑连手机，把 APK 拖进手机存储
- **直接在手机浏览器下载**：手机上打开 GitHub 下载（比较麻烦，不推荐）

安装时会提示"未知来源应用"，按系统提示 **允许** 即可。
Android 8.0 以上的手机都支持（本项目要求最低 Android 8.0）。

## 1.8 以后改了代码怎么重新打包

1. 回到仓库页面，进入你改的那个文件
2. 点右上角 ✏️ 铅笔图标（Edit）→ 改内容 → 底部 **Commit changes**
3. Actions 会自动重新构建
4. 重新走 1.6 下载

如果是大改动，也可以把整个文件删了重新拖上传。

***

# 第二节 · 本地编译（Android Studio）

## 2.1 下载安装 Android Studio

官方地址（二选一，国内推荐第二个）：

```
https://developer.android.com/studio
https://developer.android.google.cn/studio      ← 国内镜像，通常更快
```

安装时注意三点：

- 安装类型选 **Standard（标准）**
- 一路 Next，让它自动下载 Android SDK（约 3\~5 GB）
- **安装路径不要有中文和空格**，比如 `D:\Android\`

> 关于 JDK：**不用单独装**。Android Studio 自带 JDK 17，本项目正好需要 17。

## 2.2 首次启动

1. 打开 Android Studio → 弹窗问是否导入设置 → 选 **Do not import settings** → OK
2. 会走一个 Setup Wizard，一路 Next，直到看见欢迎页
3. 如果它提示要下载 SDK，就让它下完（进度条在右下角）

## 2.3 打开项目

1. 把 `FluxM3U8-Android` 文件夹解压到**不含中文和空格**的路径，如 `D:\Code\`
2. Android Studio 欢迎页点 **Open**（或顶部 File → Open）
3. 选中 **`FluxM3U8-Android`** **这个文件夹本身**（不是里面的 app）
4. 右下角弹窗点 **Trust Project（信任项目）**

## 2.4 等待 Gradle 同步

打开后底部会有进度条在跑，第一次要下载 Gradle 和一堆依赖，**5\~20 分钟**。

判断成功的标准：底部状态栏**没有转圈、没有红色报错**。

> 如果一直卡住或超时，看第三节第 1 条（配国内镜像）。

## 2.5 运行到手机（推荐，比打包快）

**手机上先操作一次：**

1. 设置 → 关于手机 → **版本号**，连续点 7 次（会提示"您已处于开发者模式"）
2. 回到设置 → 系统/更多设置 → **开发者选项** → 打开 **USB 调试**
3. 数据线连电脑，手机上弹窗点 **允许 USB 调试**

**电脑上：**

1. Android Studio 顶部中间的设备下拉框，应该出现你的手机型号
2. 点右边的绿色三角 **▶ Run**
3. 等编译完成，手机上会自动装好并打开

**用模拟器（没手机也行）：**

1. 顶部 **Tools → Device Manager → Create device**
2. 选机型（如 Pixel 6）→ Next
3. 系统镜像选 **Android 13（Tiramisu，API 33）** 或更高 → 点 Download 下载
4. 创建完点 ▶ 启动模拟器，再点 ▶ Run

## 2.6 打包 APK（装到别人手机）

顶部菜单 **Build → Build Bundle(s) / APK(s) → Build APK(s)**

完成后右下角弹窗点 **locate**，文件在：

```
app/build/outputs/apk/debug/app-debug.apk
```

## 2.7 打正式签名版（要发布才需要）

日常自用**不用做这步**，debug 版功能完全一样。

只有你想"正经发布"时才需要签名：

1. **Build → Generate Signed Bundle / APK**
2. 选 **APK** → Next
3. 点 **Create new\...**：
   - Key store path：选位置保存 `flux.jks`
   - Password：自己设，**务必记牢**
   - Alias：填 `flux`，密码同上
   - Certificate 里至少填一项（Organization 随便写）
4. Next → 选 **release** → Create

> ⚠️ 密钥文件一旦丢失，以后就无法再更新同一个 App，**一定要备份**。
> 而且千万别把 `.jks` 传到网上（`.gitignore` 已经排除了）。

***

# 第三节 · 常见问题排查

## 云编译类

**Q：Actions 页面是空的，什么都没有**
→ 99% 是 `.github/workflows/build-apk.yml` 没传上去，回到 **1.4** 手动补一个。

**Q：构建失败，日志里说 Gradle 版本不对**
→ 确认 `gradle/wrapper/gradle-wrapper.properties` 里写的是 8.2，
和 workflow 里安装的 Gradle 版本一致。

**Q：构建失败，提示 SDK license 没接受**
→ workflow 里有 `yes | sdkmanager --licenses`，正常会自动处理。
如果还失败，一般是网络问题，重跑一次（Actions 页面点 **Re-run jobs**）。

**Q：点了上传但没反应**
→ 网页上传不支持 zip 包，**必须先解压**。也不支持一次上传超过 100 个文件。

## 本地编译类

**Q1：Gradle 同步卡住 / 下载超时**（最常见）

国内连 Google Maven 慢。打开 `settings.gradle.kts`，把 `repositories` 块改成：

```kotlin
repositories {
    maven { url = uri("https://maven.aliyun.com/repository/google") }
    maven { url = uri("https://maven.aliyun.com/repository/public") }
    maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
    google()
    mavenCentral()
}
```

改完点右上角出现的 **Sync Now**。

**Q2：提示 "SDK location not found"**

**File → Project Structure → SDK Location**，设置 Android SDK 路径：

- Windows：`C:\Users\你的用户名\AppData\Local\Android\Sdk`
- macOS：`~/Library/Android/sdk`

**Q3：提示 "compileSdk 34 not installed"**

**Tools → SDK Manager → SDK Platforms**，勾选 **Android 14 (API 34)** → Apply。

**Q4：提示 "Unsupported class file major version"**

JDK 版本不对。**File → Settings → Build, Execution, Deployment
→ Build Tools → Gradle → Gradle JDK**，选 **jbr-17**（AS 自带的那个）。

**Q5：Compose 相关报错**

确认 `app/build.gradle.kts` 里这两处没被动过：

```kotlin
composeOptions { kotlinCompilerExtensionVersion = "1.5.8" }
kotlinOptions { jvmTarget = "17" }
```

**Q6：装到手机上闪退 / 下载不了**

- Android 13+ 首次打开要**允许通知权限**，否则看不到下载进度通知
- 某些站点需要防盗链：新建任务 → 展开高级选项 → 填 **Referer**（视频播放页地址）
- 锁屏后掉速：设置页「后台与省电」里加白名单，详见 README 第八节

***

# 第四节 · 版本对照表（改版本前先看这个）

这几个版本是**强绑定**的，乱改会报各种莫名其妙的错：

| 组件                     | 版本     | 备注                          |
| ---------------------- | ------ | --------------------------- |
| Android Gradle Plugin  | 8.1.4  | 根目录 `build.gradle.kts`      |
| Kotlin                 | 1.9.22 | 根目录 `build.gradle.kts`      |
| Compose Compiler       | 1.5.8  | **必须**配 Kotlin 1.9.22       |
| compileSdk / targetSdk | 34     | 需要装 Android 14 SDK          |
| minSdk                 | 26     | Android 8.0 及以上             |
| Gradle                 | 8.2    | `gradle-wrapper.properties` |
| JDK                    | 17     | 低了编译不过                      |

**改 Kotlin 版本时，Compose Compiler 必须跟着改**，对应关系查这里：
`https://developer.android.com/jetpack/androidx/releases/compose-kotlin`

***

## 还有问题？

按这个顺序自查：

1. Actions 里的红色日志，往下翻找带 `Caused by:` 或 `FAILED` 的行 —— 真正的错误在那附近
2. 本地编译的话，先试 **File → Invalidate Caches → Invalidate and Restart**（专治各种玄学问题）
3. 确认 JDK 是 17、Gradle 是 8.2、网络能连上依赖源

本地编译调试版

cd d:\J\xiangmu\FluxM3U8-Android

.\gradlew\.bat assembleDebug

本地编译正式版

cd d:\J\xiangmu\FluxM3U8-Android

.\gradlew\.bat assembleRelease
