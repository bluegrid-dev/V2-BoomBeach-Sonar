# 模型与数字参考

`Build.ps1` 将两个文件嵌入 `dist/sonar.jar`，设备侧 Java 直接读取。设备侧运行无需 Python、GPU、模型服务或 JNI。

## `cells.bin`

棋盘图像分类用的小型 CNN。输入为以目标格为中心、覆盖 2.5 格宽高的 64×64 RGB 截图裁剪，各通道除以 255；输出为盖板、水、艇体和遮挡特效四类。

结构为 Conv8/2 → Conv8/2 → Dense32 → Dense4，共 66,508 个参数。卷积使用 3×3 核、步长 2、边缘补零，卷积层和第一层全连接使用 ReLU。推理实现见 `src/sonar/CellModel.java`。

文件共 266,036 字节，采用 Java `DataInputStream` 大端格式：32 位魔数 `0x53434E32`，随后是 `w1, b1, w2, b2, w3, b3, w4, b4` 的 32 位浮点值，数组长度由推理代码固定。

SHA-256：`9a6a2ca675763ba233416eee1955433d1b2d9e17380a8c5aa7e1bc8e4f078ca0`。

## `hud.bin`

弹药数字的截图图像参考，与棋盘 CNN 分开使用。实现见 `src/sonar/Digits.java`。

文件采用大端格式：32 位魔数 `0x53484431`、32 位参考数量，随后每条是 32 位数字值、32 位浮点宽高比、12×20 个单字节布尔像素。

运行时还用 Montserrat 和设备自带字体生成数字参考。字体许可见 [THIRD_PARTY.md](../THIRD_PARTY.md)。

SHA-256：`b5465b5c394f0288c3b75397fa2f9a7aec37e8e4e551adf5c091b66ceead8427`。

两个模型文件采用项目的 [PolyForm Noncommercial License 1.0.0](../LICENSE)。仅限该许可允许的非商业用途，禁止商业使用、收费出售和商业分发；具体范围、例外与条件以完整条款为准。
