# 第三方依赖说明

- WebRTC Android 预编译包：`io.github.webrtc-sdk:android:144.7559.15`，https://github.com/webrtc-sdk/android 。WebRTC 原始项目采用 BSD-style 许可证，分发包还包含第三方组件；发布前应随选定二进制核对并附带其完整许可证与第三方告知。
- AndroidX / Jetpack Compose：Android Open Source Project，Apache-2.0。
- Kotlin / kotlinx.coroutines：JetBrains，Apache-2.0。
- Gradle Wrapper：Gradle，Apache-2.0。
- JUnit：测试用途，EPL-1.0。

开源实现调研文档列出的其他项目是设计参考，不表示本产品已经引入这些 SDK 或取得额外商标/商业授权。本文件是依赖清单，不替代二进制分发所需的完整 NOTICE。


## 界面角色素材（0.2.0）

两张交互插画由 ImageGen 按用户批准的线条小狗角色参考重新绘制，用于当前测试版界面。文件为 `web/assets/puppies-home.png`、`puppies-wait.png`，安卓使用同一资产。它们不是本项目原创角色，也不是开源依赖自带素材；本记录不代表取得该角色的商业授权。

角色外观参考来源：LINE STORE [小白](https://store.line.me/stickershop/product/22723957/zh-Hant)、[小金毛](https://store.line.me/stickershop/product/35735145/zh-Hant)。批准稿、生成提示与来源保存在 `.impeccable/mocks/interaction/` 及 PNG 元数据中。

网页标题字形：ZCOOL KuaiLe，SIL Open Font License 1.1。来自 Google Fonts，按固定标题文本子集化并自托管，运行时不请求字体服务；许可证随资产保存在 `web/assets/title-OFL.txt`。
