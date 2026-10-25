# 小米 MiTV-MTSU0（codename interstellar）

小米电视 S65 Mini LED 2026 至尊版。

- Android 14（SDK 34），HyperOS TV，固件 `OS3.0.119.0.UTSMATV`，`ro.board.platform` 是 `mt5878`，arm64-v8a。
- 面板 3840×2160（4K），系统 override 到 1920×1080，320dpi（960×540dp）。`MemTotal` 3894908 kB，约 3.7 GB。
- 原桌面：`com.mitv.tvhome/.MainActivityUserMode`（PatchWall）。备用桌面：`com.xiaomi.mitv.settings/.entry.FallbackHome`。
- 安装的 flavor：`standard`。`adb install -r` 直接成功，不需要 TCL 那套推送流程。实测版本 1.31。

## 和 MiTV-MFFU0 的差异

| | MiTV-MFFU0（finch） | MiTV-MTSU0（interstellar） |
|---|---|---|
| 固件 | OS4.0.11.0.UFFMATV | OS3.0.119.0.UTSMATV |
| `ro.board.platform` | mt5877 | mt5878 |
| 面板 | 1920×1080 / 320dpi | 3840×2160，override 到 1920×1080 / 320dpi |
| 内存 | 约 4 GB | 约 3.7 GB |
| ABI | arm64-v8a | arm64-v8a |

HOME 锁定、屏保、待机唤醒的结论和 MFFU0 一致，见 [xiaomi-mitv-mffu0.md](xiaomi-mitv-mffu0.md)。

## HOME 锁定

- `cmd role add-role-holder` / `cmd package set-home-activity` 都不报错，但 `resolve-activity` 仍然返回 `com.mitv.tvhome/.MainActivityUserMode`，和 finch 一样。
- `install.sh` 因此转用桌面守护，输出 `The firmware still sends Home to com.mitv.tvhome/.MainActivityUserMode; turning on Aerial Home Guard.`。
- 装完后 `enabled_accessibility_services` 里只有 `app.xinghe.tv/app.xinghe.tv.guard.HomeGuardService`。这台的小爱（`com.xiaomi.voicecontrol`）虽然装着，但它的无障碍服务本来就没启用，安装过程没有替它打开；`accessibility_enabled` 为 `1`。

## 开机自启

- 2026-10-22 实测 `adb reboot`：22:28:08 发出重启，22:29:43 第一次采到 `sys.boot_completed=1`（脚本先固定等了 95 秒，更早的时间点没有采样）。
- **开机后没有按任何键**，连续 5 次采样（间隔 4 秒）前台都是 `app.xinghe.tv/app.xinghe.tv.MainActivity`，没有落到 PatchWall：

  ```text
  mCurrentFocus=Window{c34488a u0 app.xinghe.tv/app.xinghe.tv.MainActivity}
  ```

- 开机后 `settings get global adb_enabled` 仍是 `1`（装了 `KEEP_ADB=1`）。
- 未停用任何系统应用，`pm list packages -d` 为空。

## 待机与唤醒

- 实机确认待机后唤醒能回到 Aerial。没有像 MFTR0 那样单独记录待机时长和复活耗时。

## 屏保

- `screensaver_enabled=0`、`screensaver_components=null`，默认组件是 `com.android.deskclock/com.android.deskclock.Screensaver`，但屏保本身是关的。
- 所以 `install.sh` 不会改写屏保设置，Aerial 靠桌面空闲时自己启动屏保。

## 信号源

- `dumpsys tv_input` 注册了 `HW5/6/7/8`（HDMI 1/2/3/4）、`HW0`（DTMB）、`HW1`（ATV）、`HW2`（AV）。
- 另外还有一组 `HW14–17`，`TvInputHardwareInfo` 里同样报 `hdmi_port=1..4`，和 `HW5–8` 重复。`DeviceProfile` 里没有 MTSU0 的条目，走的是默认映射（HDMI 的 `HWn` 对应小米播放器 source id `n+18`）。
- 信号源切换在实机上确认正常。

## 安装记录

```bash
adb connect <电视IP>:5555
curl -fsSL https://aerial.qust.me/install.sh | KEEP_ADB=1 TV=<电视IP> bash
```

输出：

```text
Installing .../aerial-standard.apk
Aerial will switch ADB debugging back on whenever the TV turns it off.
The firmware still sends Home to com.mitv.tvhome/.MainActivityUserMode; turning on Aerial Home Guard.
Home Guard is running. Press Home on the remote.
```

验收（重启后复测，`versionName=1.31`）：

```text
$ adb shell "dumpsys package app.xinghe.tv | grep versionName"
    versionName=1.31
$ adb shell settings get secure enabled_accessibility_services
app.xinghe.tv/app.xinghe.tv.guard.HomeGuardService
$ adb shell "dumpsys accessibility | grep -c -E 'label=(Aerial|app.xinghe.tv)'"
1
```
