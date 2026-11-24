# ElishPenBridge

一个 LSPosed / libxposed API 102 模块，让 ColorOS 系统识别小米手写笔，并把笔身实体按键映射成 ColorOS 手写笔手势。

解决的场景：在小米平板上刷入 ColorOS 移植包后，内核已经能驱动小米手写笔（可以正常写字），但系统自带的「智能外设」app 不认可这支笔，导致设置页变灰、侧键无功能。本模块通过 hook 上层状态与按键事件，补齐这条链路。

## 功能

### system_server 层
- 拦截 `Xiaomi Smart Pen Keyboard` 设备的 `KEY_PAGEUP` / `KEY_PAGEDOWN` 按键，转成 ColorOS 手写笔手势广播：
  - `com.oplus.ipemanager.action.PENCIL_SINGLE_CLICK`
  - `com.oplus.ipemanager.action.PENCIL_DOUBLE_CLICK`
- 强制手写笔相关的 `Settings.Global` 开关为开启状态
- 屏蔽原始翻页键传递，避免侧键变成页面滚动

### 智能外设 app 层（`com.oplus.ipemanager`）
- 伪造笔的连接状态、电量（优先读取蓝牙真实电量）、MAC 地址、设备类型
- 设置页每次 `onResume` 注入"已连接 / 电量"，并防止页面自动退出
- 设备信息页填入型号、序列号、硬件版本、固件版本
- 隐藏当前设备没有硬件支持的功能入口（激光笔、长捏笔身、息屏速记、上下滑动、随手写、自动更新等）

### 多版本兼容
通过候选类名 + 版本检测，同时支持 ColorOS 15 / 16 / 17 的智能外设 app：

| 版本 | IPeManager versionName | 说明 |
|---|---|---|
| C15 | 15.0.65 | 无单击广播，两个按键都映射到双击 |
| C16 | 16.4.10 | 无单击广播，两个按键都映射到双击 |
| C17 | 17.4.0 | 完整支持单击/双击 |

模块通过 `m3.b` / `PencilBleManager` / `d2.b` 等特征类判断版本，写入 `Settings.Global elish_ipe_version`，供 system_server 选择对应的按键策略。

## 使用

1. 安装 LSPosed（2.1.0+，需支持 libxposed API 102）
2. 安装本模块 APK
3. 在 LSPosed 中启用模块，勾选作用域：
   - `系统框架`（system）
   - `com.oplus.ipemanager`
4. 重启设备

## 配置

所有配置项都在 `Settings.Global`，按需设置后重启相关进程：

| Key | 默认 | 说明 |
|---|---|---|
| `elish_pen_primary_keycode` | 92 | 主键 keycode（PAGEUP） |
| `elish_pen_secondary_keycode` | 93 | 副键 keycode（PAGEDOWN） |
| `elish_pen_verbose_log` | 0 | 调试日志开关，1 为开启 |

```bash
adb shell settings put global elish_pen_verbose_log 1
```

## 编译

本项目不使用 Gradle，直接用 Android SDK 工具链编译。需要：

- Android SDK Build-Tools 36.0.0+
- Android Platform 37
- JDK 17+
- Python 3
- libxposed API 102 的 `classes.jar`（从 LSPosed 项目获取）

```bash
export ANDROID_SDK_ROOT=/path/to/Android/Sdk
export LIBXPOSED_API_JAR=/path/to/classes.jar
./build.sh
```

输出：`ElishPenBridge-v<version>.apk`

> 注意：`classes.jar` 只作为编译期的 `--lib`，不会被打进 dex。
> 如果 dex 里出现 `io/github/libxposed` 的类，LSPosed 会拒绝加载模块。

## 项目结构

```
src/main/
├── AndroidManifest.xml
├── java/com/elish/penbridge/
│   ├── Main.java          模块入口，按进程分发 hook 安装
│   ├── SystemHooks.java   system_server 层：按键拦截与手势广播
│   ├── SettingsHooks.java Settings.Global 守护
│   ├── IpeHooks.java      智能外设 app 层：状态伪造与 UI 处理
│   └── Util.java          反射/日志/多版本兼容工具
├── res/values/strings.xml
└── resources/META-INF/xposed/
    ├── module.prop       API 102 模块元数据
    ├── scope.list        静态作用域
    └── java_init.list    入口类声明
```

## 已知限制

- 不修改 AES 2.0 笔屏协议，也不改 HID 描述符/内核驱动
- 不支持"随心圈"（需要笔身捏压传感器 + 圈选交互，实体按键无法完整模拟）
- 不支持激光笔（小米二代笔无激光发射硬件，入口已隐藏）
- C15/C16 没有单击广播，侧键行为与 C17 略有差异

## License

MIT
