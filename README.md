# V2 海岛奇兵国际服声呐工具 | Boom Beach Sonar Automation

在雷电模拟器中自动识别声呐棋盘、侦察潜艇位置并使用蓝弹攻击，通过 Windows 窗口控制开始、停止和运行状态。

**[下载 v2.0.0 ZIP](https://github.com/bluegrid-dev/V2-BoomBeach-Sonar/archive/refs/tags/v2.0.0.zip)** · [查看 Releases](https://github.com/bluegrid-dev/V2-BoomBeach-Sonar/releases) · [仅限非商业用途](LICENSE)

主要环境：Windows 10/11 · 雷电9 · 1280×720 · DPI 320 · Root 最高权限准备好雷电和国际服

**下载 ZIP → 完整解压 → 双击 `启动.cmd` → 点击“开始”**。首次启动自动准备 Python 环境和本机配置

游戏地图请使用默认（原始）皮肤；部分皮肤会改变画面视角，可能导致识别或点击位置不匹配。

正常情况下，单关约 1-2 分钟，实际耗时会因关卡、网络和设备状态有所波动。
## 主要功能

- 自动截图、识别棋盘与舰队，根据画面信息推断潜艇位置。
- 支持红弹离线侦察、蓝弹离线侦察和正常蓝弹攻击。
- 胜利后自动进入下一海域，可设置完成关数或连续运行。
- 窗口显示棋盘、当前阶段、用时和日志，提供开始、停止及侦察模式切换。
- 首次启动自动准备运行环境、发现雷电 ADB 并生成本机配置。

## 运行环境

| 项目 | 要求 |
| --- | --- |
| 电脑 | 64 位 Windows，使用系统自带的 PowerShell |
| 模拟器 | 雷电 9，Android 9 |
| 游戏 | 海岛奇兵国际服 |
| 模拟器设置 | 1280×720，DPI 320  开启 ADB 和 Root |
| 网络 | 正常游戏需要联网；首次缺少 Python 时需访问 Python 官网下载环境 |

普通用户无需手动安装 Python、Git、JDK 或 Android SDK，也无需填写 JSON。雷电模拟器与游戏需自行安装并完成上述设置。

## 下载与启动

1. 下载上方的 **v2.0.0 ZIP**；也可在 [Releases](https://github.com/bluegrid-dev/V2-BoomBeach-Sonar/releases) 页的 **Assets → Source code (zip)** 下载。仓库的 **Code → Download ZIP** 提供主分支文件。
2. **完整解压** ZIP，在解压后的文件夹中操作，不要直接在压缩软件里运行启动文件。
3. 启动雷电，打开海岛奇兵正式国际服，再双击 **`启动.cmd`**。
4. 首次启动会检查环境；缺少可用 Python/Tk 时自动下载并校验。若有多台在线设备，按提示输入正式国际服设备的编号。
5. 控制窗口打开后点击 **“开始”**。以后再次使用，打开雷电后双击同一个启动文件即可。

`Source code (zip)` 中已包含启动脚本、模型和设备侧 JAR，不需要编译。首次准备环境可能需要等待，进度和错误会显示在启动窗口中；准备完成后复用已有环境，不修改系统 PATH。

## 日常使用

- **停止：** 点击窗口“停止”或双击 `停止.cmd`，等待显示已停止后接管游戏。已经发出的动作会先完成确认与通信收尾。
- **完成关数：** 右键窗口打开“运行设置”。`1` 表示胜利并进入下一关后停止，`0` 表示连续运行。
- **侦察方式：** 勾选“蓝弹离线侦察”优先蓝侦；不勾选优先红侦。红弹未解锁时暂用蓝侦，第 6 关起可用红侦。运行中修改偏好在下一次侦察生效。
- **换账号或手动跳关：** 先停止，再重新启动，从当前画面建立新状态。

离线侦察会暂时隔离目标游戏的通信，读取结果后恢复通信；确认目标仍用正常蓝弹攻击。

## 常见问题

### 提示没有在线设备，或已配置的设备不在线

先启动对应雷电实例并开启 ADB，再运行 `启动.cmd`。已有配置时沿用原设备，不会自动切到其他设备。

### 检测到多台设备

按启动窗口中的编号选择正式国际服所在设备，选择会保存，下次沿用。需要更换已保存的设备时，见下方“高级配置与开发”。

### Python 下载失败，或控制窗口没有打开

查看启动窗口中的具体原因。下载失败时确认可以访问 Python 官网，再重新运行 `启动.cmd`；缺少文件时重新下载并完整解压 ZIP。

## 许可

V2 海岛奇兵声呐自动化工具由 bluegrid-dev 独立开发并维护，项目作者保留原创代码、脚本、文档、识别模型及相关成果的著作权。

允许个人或项目在非商业用途范围内免费使用、学习、复制、修改、二次开发和再发布，包括制作及分享派生版本。
二次开发与再发布
基于本项目进行修改或公开派生版本时，应遵守以下要求：
- 保留项目来源：注明原项目为 V2-BoomBeach-Sonar，保留 bluegrid-dev 署名。
- 保留原仓库地址：bluegrid-dev/V2-BoomBeach-Sonar。
- 保留版权声明：保留原项目版权信息及必要的第三方许可声明。
- 明确修改归属：应说明对原项目进行了哪些修改，不得将原作者的原创成果声明为完全自行原创。
- 版本：未经授权，不得冒充或暗示派生版本由 bluegrid-dev 开发、认可或维护。
商业使用限制
未经 bluegrid-dev 另行授权，禁止将本项目及其派生版本用于商业用途、收费出售或商业分发。

具体授权范围及使用条件以项目 LICENSE 文件为准。

## 高级配置与开发

<details>
<summary>本机配置、命令行与源码构建（普通用户可跳过）</summary>

首次启动自动生成 `local.json`。自定义环境时可参考 `local.json.example`：

| 字段 | 内容 |
| --- | --- |
| `adb` | ADB 可执行文件完整路径 |
| `serial` | 国际服设备的实际 ADB 序列号 |
| `python` | 带 Tk 的 `pythonw.exe` 完整路径 |
| `jdk` | JDK 安装目录，仅自行构建源码时添加 |
| `androidSdk` | Android SDK 安装目录，仅自行构建源码时添加 |

`local.json` 保存本机设置，`work/` 保存运行设置和日志，`.runtime/` 保存自动下载的 Python 环境。已有有效配置和其他配置字段会保留，这些本机文件不应提交到仓库。设备侧运行目录为 `/data/local/tmp/sonar-independent`。

指定 ADB 位置或更换设备：

```powershell
& .\Setup.ps1 -AdbPath '实际的 adb.exe 路径' -Serial '实际设备序列号'
```

添加 `-Action Configure` 可只准备环境和配置，不打开窗口。

命令行控制（在本目录运行）：

```powershell
& .\Control.ps1 -Action Start -StopAfterLevels 1
& .\Control.ps1 -Action Status
& .\Control.ps1 -Action SetMode -ScoutMode Blue
& .\Control.ps1 -Action Stop
```

截图、识别、棋盘状态、求解、点击和通信控制在 Android 设备侧执行，Windows 窗口读取并显示状态。

仅开发构建需要 JDK、Android SDK platform 36 与 build-tools 35.0.0。在 `local.json` 添加并填写 `jdk` 和 `androidSdk` 后运行：

```powershell
& .\Build.ps1
```

脚本编译 `src/sonar/*.java`，转换为 Android DEX，生成 `dist/sonar.jar`，并嵌入 `models/cells.bin` 与 `models/hud.bin`。所附 JAR 由本目录源码构建。模型格式见 [models/README.md](models/README.md)，文件校验值见 [SHA256SUMS.txt](SHA256SUMS.txt)。

</details>

## 说明

- 根据可见画面读取棋盘、舰队和资源，求解器；识别仍可能误判，画面反馈不能等同于服务器确认。
- 标准视图仅调整当前设备的 `CAMERA_DISTANCE`、`CAMERA_SHAKE`，会话结束时恢复原文件，不附带游戏配置、商店配置或游戏资源。
- 棋盘矛盾、结果未确认或弹药读数异常时沿用一次画面复核；仍不能确认则停止并保留记录。首次运行从当前画面建立新状态，不自动重放旧动作。
