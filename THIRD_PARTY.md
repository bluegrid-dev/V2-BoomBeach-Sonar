# 来源与第三方许可

除下列另有许可说明的第三方内容外，项目源码、脚本、文档、模型权重与数字识别参考为项目自有内容，采用根目录的 [PolyForm Noncommercial License 1.0.0](LICENSE)。仅限该许可允许的非商业用途，禁止商业使用、收费出售和商业分发；具体范围、例外与条件以完整条款为准。

Required Notice: Copyright (c) 2026 bluegrid-dev

## Montserrat 字体

- 文件：`assets/Montserrat-Bold.ttf`。
- 来源：[Montserrat 官方仓库固定提交](https://github.com/JulietaUla/Montserrat/tree/555facfb2a18c72c3c0380f0d9c0f060453a9058)，提交号同时保存在 `assets/source-commit.txt`。
- 版权：Copyright 2024 The Montserrat.Git Project Authors。
- 许可：SIL Open Font License 1.1；完整版权声明与许可正文见 [assets/OFL.txt](assets/OFL.txt)。字体保持原样，按其自身许可分发。

## 运行环境

程序使用 Android/AOSP 的 SurfaceControl、InputManager 系统接口和 Linux SOCK_DIAG UAPI。Android SDK、JDK、ADB 及 Android、Windows 系统字体由运行环境提供，不包含在发行包中。

Python/Tk 可由现有环境提供；缺少时，`Setup.ps1` 从 [Python 官网](https://www.python.org/ftp/python/3.13.16/)下载 3.13.16 x64 完整运行时 ZIP，以[官方清单](https://www.python.org/ftp/python/3.13.16/windows-3.13.16.json)中的 SHA-256 校验，并在项目目录解压使用。Python 遵循其 [PSF 许可及附属许可](https://docs.python.org/3.13/license.html)，下载目录中的许可文件保持原样。公开源码包不包含下载的 Python 运行时。
