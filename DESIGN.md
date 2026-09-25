---
name: 同屏（工作名）
description: 电脑网页与原生 Android 双人共享屏幕的已实现视觉规范
colors:
  home-primary: "#A65C2B"
  home-background: "#FFF9F0"
  home-text: "#49392D"
  home-muted: "#786453"
  home-container: "#F1DCC6"
  home-container-text: "#624327"
  home-surface-variant: "#F0E6D9"
  web-entry: "#FFFDF8"
  web-home-line: "#E6D7C6"
  android-home-outline: "#9B8875"
  call-primary: "#F2D28D"
  call-primary-text: "#30291C"
  call-background: "#1D2331"
  call-text: "#F7F0DF"
  call-muted: "#B6C0CF"
  call-container: "#293345"
  web-call-line: "#394355"
  android-call-outline: "#8D9AAF"
  web-video-background: "#10151F"
  android-video-background: "#000000"
  web-call-danger: "#FFB4AB"
  web-call-danger-container: "#492C32"
typography:
  web-display:
    fontFamily: '"Tongping Title", "PingFang SC", sans-serif'
    fontSize: "clamp(32px, 3.2vw, 46px)"
    fontWeight: 400
    lineHeight: 1.25
    letterSpacing: "-.025em"
  web-body:
    fontFamily: '-apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif'
    lineHeight: 1.6
  web-room:
    fontFamily: "ui-monospace, monospace"
    fontSize: "28px"
    fontWeight: 600
rounded:
  web-control: "8px"
  web-panel: "12px"
  android-preview: "12dp"
  android-status: "16dp"
spacing:
  web-desktop-gutter: "40px"
  web-compact-gutter: "24px"
  android-home-gutter: "24dp"
  android-call-gutter: "20dp"
  android-related: "12dp"
  android-group: "16dp"
components:
  web-button-home:
    backgroundColor: "{colors.home-primary}"
    textColor: "#FFFFFF"
    rounded: "{rounded.web-control}"
    padding: "10px 19px"
  web-button-call:
    backgroundColor: "{colors.call-primary}"
    textColor: "{colors.call-primary-text}"
    rounded: "{rounded.web-control}"
    padding: "10px 19px"
  web-entry:
    backgroundColor: "{colors.web-entry}"
    rounded: "{rounded.web-panel}"
    padding: "36px"
---

# Design System: 同屏

## Overview

**Creative North Star: "一起看，慢慢聊"**

奶油色首页、可可棕主操作与两只相互陪伴的小狗构成入口；通话转为海军蓝底、奶黄色主操作，让房间状态、共享画面及通话控制成为焦点。电脑网页与原生 Android 共用配色与角色资产，各自保持平台布局与控件语言。「同屏」是工作名。

本记录依据 `PRODUCT.md`、`.impeccable/web.brief.md`、`web/index.html`、`web/style.css` 及 `app/src/main/java/cn/screenshare/app/MainActivity.kt`。已批准的是 `.impeccable/mocks/interaction/duo-interactions-v1.png` 中的角色形象和互动，不是整页布局稿。产品边界为 Android 与电脑浏览器双人房间、WebRTC 直连优先及 TURN 备用中转，不延伸到 iOS、多人与 SFU。

截图证据位于 `.impeccable/review/`：`desktop-home.png`、`desktop-call.png`、`desktop-sharing.png`、`android-home.png`、`android-call.png`、`android-sharing.png`。本次记录以源码为准；Android 最后一次系统栏对比度修正已在通话与共享两种状态重新截图验收，奶油色导航栏背板上的深色按钮清晰可见。截图不代表真机性能、听感或 Windows 系统音频已经验收。

**Key Characteristics:**
- 奶油与可可棕承载首页，海军蓝与奶黄承载通话。
- 两只小狗在首页一起看电脑，等待时牵手招呼，视频画面出现后退出主舞台。
- 网页采用桌面双栏入口；Android 保持原生单列、滚动内容与常驻通话控制。

## Colors

### Primary

首页主操作使用 `home-primary` 与白字；通话主操作使用 `call-primary` 与 `call-primary-text`。两者表示当前场景中的主要动作，不表示系统浅色或深色偏好。

### Neutral

首页由 `home-background`、`home-text` 与 `home-muted` 组成暖色阅读环境。网页入口面板使用 `web-entry`，细线使用 `web-home-line`；Android 原生浅色主题使用 `home-container` / `home-container-text` 与 `home-surface-variant`，描边为 `android-home-outline`。

通话的 `call-background` / `call-text` 承载主内容；`call-container` 用于控件与 Android 状态面板，`call-muted` 承载辅助信息。网页细线与悬停底使用 `web-call-line`，Android 描边使用 `android-call-outline`。网页画面舞台使用 `web-video-background`，Android 视频容器使用 `android-video-background`。

网页通话结束按钮使用专用危险色与暗红底。Android 错误、错误容器及原生控件未显式覆盖的色彩继承 Material 3；不要将其库默认值列为项目品牌 token。

**The Call State Rule.** 网页进入通话后应用深色场景；Android 由 `callState.active` 驱动主题。Android 通话状态栏使用深色背景方案，非通话使用浅色方案；导航栏始终采用奶油背板与深色图标。外层 Box 的背景及 `navigationBarsPadding()` 为强制 edge-to-edge 提供实际导航栏背板。

## Typography

**Display Font:** 网页首页标题使用本地 `web/assets/title.ttf`，CSS 名称为 Tongping Title，实际字体为 ZCOOL KuaiLe。它是固定首页标题文案的字体子集，授权文件为 `web/assets/title-OFL.txt`；新增标题文字时应检查字形覆盖。字体加载使用 swap。

**Body Font:** 网页正文和全部操作控件使用系统 sans 字体栈。Android 采用 Material 3 默认 Typography 与平台字体，不使用网页标题字体，也没有自定义字体比例。

**Label/Mono Font:** 网页输入与房间号采用等宽字体；Android 房间输入和通话房间号显式使用 `FontFamily.Monospace`。

### Hierarchy

- 网页标题尺度见 frontmatter；二级标题为 23px / 1.4，介绍正文为 18px / 1.85，提示为 13px。输入框为 26px 等宽字、0.1em 字距，通话房间号见 `web-room`。
- Android 首页标题为 `headlineLarge`、SemiBold；房间号为 `headlineMedium`；房间输入和状态面板标题为 `headlineSmall`。
- Android 顶栏为 `titleLarge`、Bold，加入区标题为 `titleLarge`，创建按钮为 `titleMedium`；介绍为 `bodyLarge`，辅助文字为 `bodySmall`，状态及统计使用 label 样式。具体字号、行高和字距继承当前 Material 3 版本。

## Layout

网页 header 与 main 最大宽度为 1280px。桌面水平内边距见 frontmatter；首页为 1.2fr / 1fr 两列、72px 间距、48px 上下内边距、最小高 650px，左侧标题和角色图，右侧创建/加入面板。首页角色容器为 350 × 235px，等待角色容器为 230 × 165px，均以 contain 显示，不拉伸。

网页 900px 及以下使用 24px 水平边距、32px 首页间距和 580px 最小高度，入口面板内边距 26px，房间栏和通话操作改为纵向。640px 及以下首页单列、上下内边距 32px，入口面板内边距 24px，首页角色高 170px、等待角色高 120px；这是响应式降级，不扩大产品到手机浏览器首要支持范围。

网页通话先显示房间信息，再显示画面舞台和控制区。舞台高为 `clamp(280px, 52vh, 680px)`，640px 及以下为 45vh；视频使用 contain。控制区上边距 22px，组间距 20px，组内 12px（最窄断点为 8px），允许换行。全屏舞台占满宽高并去掉圆角；不要将网页常规控制区误记为固定定位。

Android 首页可滚动，水平 24dp、垂直 16dp 内边距，主要区块间距 16dp、相关内容 12dp，角色图高 132dp。顶栏避让状态栏；内容消费 Scaffold inset 并避让键盘。通话页水平 20dp、外层间距 8dp；房间信息、状态/视频和统计占据剩余高度并可滚动，内部间距 16dp。共享按钮、音频控制及画质/挂断行位于滚动区域之外。

Android 普通远端预览高 360dp，右下方全屏按钮外边距 12dp。全屏为安全区域内的黑色画面，右上退出，底部静音与挂断，外边距 16dp、操作间距 12dp。系统返回先退出全屏。Web px 与 Android dp 分别记录，不建立未经实现的跨平台等值尺度。

## Elevation & Depth

网页用表面色、细线和留白形成层次，当前样式没有 box-shadow。Android 自定义内容也采用色调表面；按钮、菜单、对话框的海拔与状态由 Material 3 负责。网页按钮背景过渡为 0.16s ease-out，在减少动态效果偏好下关闭。角色图不附加动画。

## Shapes

网页按钮与输入为 8px 圆角，入口和舞台为 12px 圆角，画面标签为 6px 圆角。Android 首次使用容器和预览为 12dp 圆角，状态面板为 16dp 圆角，其余原生控件继承 Material 3 形状。视频内层直角裁剪，普通预览由外层圆角约束。

## Components

### Entry and room input

网页入口面板包含创建按钮、分隔说明、八位房间号和加入按钮；输入框高 60px，内边距 12px 16px，入口按钮最小高 52px。Android 主按钮最小高 56dp，加入描边按钮最小高 52dp；输入单行、数字键盘、最多八位，八位完整后启用加入。两端均保留可读标签。

### Buttons and feedback

网页基础按钮最小高 46px、内边距 10px 19px；焦点使用 3px 外轮廓并外移 4px，首页为棕色，通话为奶黄。禁用使用 0.5 透明度和不可操作指针。Android 使用原生按钮状态、对话框与 Snackbar；复制成功和取消共享授权有 Snackbar 反馈。不要以网页组件示例替代原生控件。

### Characters and media stage

网页使用 `web/assets/puppies-home.png` 与 `puppies-wait.png`；Android 使用对应 `R.drawable.puppies_home` 与 `puppies_wait`。首页是两只狗靠在一起看电脑，等待是牵手招呼。图片有内容描述，不把两张独立贴图并排当作同一互动资产。

**The Screen First Rule.** 远端共享画面出现后不显示大插画。Android 本地共享状态也隐藏等待角色，保留状态文字与前往手机桌面的操作。Android 等待面板内边距 24dp、内容间距 12dp，内层最小高 144dp，等待角色高 140dp。

### Call controls and navigation

房间号可复制，数字按四位分组。共享按钮根据连接、当前共享方与更新状态控制可用性；停止共享保留语音，麦克风静音与屏幕媒体声音区别有说明。Android 音频设备使用原生下拉菜单，画质使用可滚动的原生对话框，顶栏状态为 polite live region；网页状态与错误使用 status / alert 语义。实际统计来自连接状态，不作为装饰数字。

Android 接收画面按比例适配、不镜像，源码提供 1–3 倍双指缩放。网页提供画质选择、静音、共享与结束通话，并说明浏览器和系统对音频共享的限制。这里记录源码行为，不把这些功能的存在当作未覆盖平台的运行验收。

## Do's and Don'ts

### Do:
- **Do** 同时维护奶油首页与深蓝通话场景，并保留各平台的原生布局边界。
- **Do** 使用已批准的两只狗互动资产，并让视频内容优先于插画。
- **Do** 保留共享状态、停止共享、静音与结束通话的明确入口。
- **Do** 将源码事实、截图证据与尚未验证的平台行为分开记录。

### Don't:
- **Don't** 将角色稿描述为已批准的完整页面布局。
- **Don't** 将 Android 主题切换描述为跟随系统深色模式。
- **Don't** 将标题字体子集用于任意新文案或全部操作控件。
- **Don't** 用虚构的延迟、帧率或跨平台音频能力装饰界面。


### 0.3.0 画质设置扩展

保留两只小狗、原配色、通话控制布局。网页在画质快捷选择下增加默认折叠的“精确调整画质”，数字输入使用正常正文字号，3 列网格在较窄窗口降为 2 / 1 列。安卓画质弹窗内容可滚动，取消/应用保持可达；无效输入提示固定在标题下。分辨率、FPS、Mbps 分开填写，目标参数与实际传输统计分开显示。禁止把目标 4K 文案当作实际收到 4K 的证明。证据见 docs/0.3.0验证记录.md。

### 0.4.0 摄像头与聊天扩展

保留主屏幕舞台与小狗资产。电脑在主舞台下方并排放置摄像头区域与聊天区，较窄窗口改为纵向。摄像头显式标注“你 / 对方”，本地网页预览镜像，关闭状态显示文字占位。消息使用纯文本呈现。安卓在原通话滚动区域显示摄像头预览，底部增加摄像头与聊天入口；聊天独立展开，保留草稿，输入和发送可达。摄像头使用独立视频轨道，不改变屏幕画质设置。


### 0.5.0 通话体验调整

维持奶油/可可首页、深蓝/奶黄通话及两只互动小狗。网页共享画面与按需展开的聊天侧栏同排，摄像头仅开启时显示小预览；画质、音频及实际统计默认折叠。安卓主画面占据可用剩余高度，底部两行常用操作；聊天与声音/连接信息使用原生底部面板，精确画质保留对话框。房间号改为紧凑单行。

全屏与小窗复用视频渲染器，显示首帧等待和网络恢复状态。聊天未读保存在通话状态中，不因全屏/小窗重新创建 UI 而重算。观看时保持亮屏，结束后释放；角色图只用于入口和无媒体的等待场景。权限错误不再替代整场通话的生命周期。

### 0.5.1 名称、图标与观看方向

中文显示名为“同屏搭子”，英文项目名仍为 Screen Companion。图标沿用已认可的白狗与黄狗角色，两只头靠在一起，爪子搭在小屏幕上；深蓝背景承接通话配色。安卓使用自适应图标及留边，网页使用同图的 256 像素版本与 64 像素 favicon；首页与等待插画保持原样。

全屏观看跟随接收画面的横竖方向，退出时恢复之前的方向设置和系统栏。渲染器与比例状态使用相同生命周期，观看区域或画面比例改变后恢复 1 倍缩放及居中。不同屏幕比例采用完整等比适配，允许黑边，禁止以裁切填满。

安卓“高清默认”以 2560×1440 尺寸上限、30 FPS、12 Mbps、清晰优先启动共享，常见长屏保持原始像素。均衡与动态流畅保留，网络受限时仍受拥塞控制。
