# 共享屏幕
<!-- impeccable:product-schema 1 -->

## Platform
adaptive

## Stack
用户在调研后的方案上授权实施：Kotlin 原生安卓、WebRTC 直连优先、Go 轻量信令与 TURN 备用中转。安卓界面使用 Jetpack Compose；电脑网页使用原生 HTML/CSS/JavaScript 与浏览器 WebRTC，无新增生产依赖。

## Product Purpose
安卓与电脑（Windows、Mac、Linux 浏览器）之间双人共享屏幕并双向语音，重点是画面清晰、低延迟和操作简单。

## Users
首版按已提出的两人远程讲解/协助场景实现；具体市场人群尚未确定。

## Capabilities and Constraints
Android 10+；电脑优先当前 Chrome/Edge，浏览器能力现场检测。取消 iOS、多人与 SFU。创建/加入房间、双向语音、单路屏幕与允许采集的媒体声音共享、麦克风静音、停止共享、挂断。仅系统授权后采集屏幕。无远程控制、录像和账号体系。需自行部署服务；真机性能与听感仍需验证。

## Product Principles
优先完成核心通信；不虚构性能数据；共享状态可见且随时停止；弱网优先语音。
