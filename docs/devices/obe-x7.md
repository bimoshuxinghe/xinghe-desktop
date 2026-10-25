# 大眼橙 OBE_NEW X7（投影仪）

- Android 9（SDK 28），Amlogic，HDMI 播放器是 `com.droidlogic.tvsource/.DroidLogicTv`。
- 资料来自贡献者 zyt0339（PR #366、#392），维护者手里没有这台机器。

## 信号源（PR #366）

`TvInputManager` 能列出 HDMI 输入，但固件里没有处理通用 `ACTION_VIEW` 直通 URI 的 Activity，点了会提示无法打开。`SystemProbe` 在通用 intent 解析不到、又装了 `com.droidlogic.tvsource` 时，改为显式启动 `DroidLogicTv`，用 `id` extra 传框架的输入 id。X7 上验证过 HDMI2（HW6，收到 `onVideoAvailable`）；HDMI1/3 没实际播放过，CEC 子输入的 id 也没试过（这条路由传的是端口的 id）。

## Apple TV 的主页键（PR #392，默认关闭）

在 Apple TV 的 HDMI 画面里按遥控器主页键，固件会回到投影仪桌面；用户想要的是回到 Apple TV 的主屏幕。

- 普通 App 拿不到 `hdmi_control` 的发送接口（要系统签名的 `HDMI_CEC` 权限）。这台的固件开放了可写的 `/dev/cec`（Amlogic 自己的原始帧接口，`/sys/class/cec/cec_version` 是 `5`），和 Linux 的 `/dev/cec0` 不是一套接口。SELinux 本来就是 permissive，Aerial 不改驱动、逻辑地址和 SELinux。
- 发送的帧：`<设备地址> 44 09`（Root Menu 按下）和 `<设备地址> 45`（松开），源地址用电视的 0。成功时 `write` 返回 0（CEC 状态，不是字节数），所以每帧只调一次 `Os.write`，用流写会重试或报错。
- 只有以下条件都满足才接管主页键，否则一切照旧：功能已打开；`/dev/cec` 可写；`tv_current_inputid`（System 或 Global 设置）给出的当前输入是已连接的 HDMI 输入，它的播放器在前台；这个输入在磁贴菜单里选了「Apple TV」，或者它下面唯一的 CEC 设备厂商号是 Apple（`0x0010fa`），读不到厂商号时名称是「Apple TV」；能从输入 id 或 CEC 信息读到设备的逻辑地址。接了功放、一个口下有几台 CEC 设备时不猜。
- 行为：按下就发 Root Menu；长按（系统长按时长，X7 上 400ms）或 400ms 内再按一次回到 Aerial（第一次按下已经发过一次，不再发第二次）。返回键不拦。打开时守护会过滤按键，即使 Aerial 是默认桌面也一样，但只在上述 HDMI 输入在前台时。

```bash
# 打开（'*' = 任何识别为 Apple TV 的输入；也可以写确切的输入 id）
adb shell am broadcast -a app.xinghe.tv.CONFIG -n app.xinghe.tv/.guard.ConfigReceiver --es cec_home_input '*'
# 别的固件：指定播放器包名和「当前输入」的设置键
adb shell am broadcast -a app.xinghe.tv.CONFIG -n app.xinghe.tv/.guard.ConfigReceiver \
  --es cec_home_input '*' --es cec_home_player '<包名>' --es cec_home_input_setting '<设置键>'
# 关闭
adb shell am broadcast -a app.xinghe.tv.CONFIG -n app.xinghe.tv/.guard.ConfigReceiver --es cec_home_input ''
```

日志 tag `AerialGuard`：识别结果（`CEC input=… manualApple=… apple=…`）、两帧的写入结果、长按和双击。`input keyevent` 注入的按键不经过无障碍过滤，要用实体遥控器或 `sendevent` 测。

验证情况：贡献者在 X7 的 standard release 上用 `sendevent` 通过 OBE_R2 遥控器的输入设备测过单按（留在 HDMI，两帧都返回 0）、长按（402ms 回到 Aerial）、双击（回到 Aerial，只发一次 CEC）；户主确认 CEC 命令能让 Apple TV 回到主屏幕；日志里是磁贴手动选择的路径（`manualApple=true`）。**没验证**：装好后用实体遥控器、过滤时其他按键、待机唤醒、只靠 CEC 识别、其他 Amlogic 机型。
