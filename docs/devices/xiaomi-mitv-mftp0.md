# 小米 MiTV-MFTP0（codename alita）

- Android 9（SDK 28），build `23.5.23.6436`（incremental `6436`），MediaTek `m7642`（`ro.product.board=alita`），32 位 ARM（`armeabi-v7a,armeabi`），内存 2704376 kB（约 2.6 GB）。
- 1920×1080，320dpi（960×540dp）。
- 原桌面：`com.mitv.tvhome/.MainActivityUserMode`（PatchWall，`versionName=3.50.1621`）。
- 安装的 flavor：`standard`。普通的 `adb install` 就能装。
- 2026-10-21 在这台电视上实测，从 1.14（`versionCode=15`）升级到 1.16（`versionCode=18`）。

## 和 Android 14 机型的差异（重要）

这台是 **Android 9 / SDK 28**，比 [xiaomi-mitv-mftr0.md](xiaomi-mitv-mftr0.md) 和 [xiaomi-mitv-mffu0.md](xiaomi-mitv-mffu0.md) 记录的 Android 14 老很多。已知的 HOME 锁定结论大致沿用，但**升级后守护会失效**，而且**复活机制完全不工作**，见下文。

## 覆盖安装/升级后 Home Guard 绑不上（最关键）

升级 App 后桌面会退回 PatchWall，**不是被小米杀掉，是无障碍服务根本没绑上**。机理是 `system_server` 持有一条失效的 APK 路径。

升级到 1.16 后 logcat 里立刻出现：

```text
W ziparchive: Unable to open '/data/app/app.xinghe.tv-EQ7BsdEf0tfGxmIzz_rO1g==/base.apk': No such file or directory
E system_server: Failed to open APK '/data/app/app.xinghe.tv-EQ7BsdEf0tfGxmIzz_rO1g==/base.apk' I/O error
E ResourcesManager: failed to add asset path /data/app/app.xinghe.tv-EQ7BsdEf0tfGxmIzz_rO1g==/base.apk
W PackageManager: Failure retrieving resources for app.xinghe.tv
```

对照实际路径：

```text
错误里的路径 : /data/app/app.xinghe.tv-EQ7BsdEf0tfGxmIzz_rO1g==/base.apk   ← 已被删除
真实的 codePath: /data/app/app.xinghe.tv-3cMB0fugJLqv9l5cVlhkpA==/base.apk   ← 升级后的新目录
```

**怎么认出这个状态**：设置里有、`dumpsys` 里没有。

```text
$ adb shell settings get secure enabled_accessibility_services
...:app.xinghe.tv/app.xinghe.tv.guard.HomeGuardService        ← 设置里在

$ adb shell dumpsys accessibility | grep -iE "label=Aerial"
（空）                                                           ← 系统里没有
```

**排查时必须看 `dumpsys accessibility`，不要看 `enabled_accessibility_services`**——设置里的记录是好的，看它会误判成「已就绪」。

### 恢复方式（已验证有效，不需要重装）

```bash
adb shell am force-stop app.xinghe.tv
sleep 3
adb shell am start -W -n app.xinghe.tv/.MainActivity
```

执行后守护立刻绑定，前台回到 Aerial。`force-stop` 让系统重新走一遍资源加载，路径就对了。也不需要重启电视。

## 通知监听绑不上（Android 9 的第二个坑）

`RevivalListener`（被杀后拉回来的机制）**在这台上无法绑定**，所以一旦守护失效，没有任何东西能自动恢复。1.16 上复测仍然如此。

```text
$ adb shell cmd notification allow_listener app.xinghe.tv/app.xinghe.tv.guard.RevivalListener
EXIT=0                                            # ← 报告成功

$ adb shell dumpsys notification | grep -A4 "Notification listeners:"
  Notification listeners:
    Allowed notification listeners:               # ← 空
    All notification listeners (0) enabled for current profiles:
    Live notification listeners (0):
```

试过的其他写法，都不行：

| 写法 | 结果 |
| --- | --- |
| `cmd notification allow_listener <COMPONENT>` | `EXIT=0`，不写入 |
| `cmd notification allow_listener <COMPONENT> 0`（positional user_id） | `EXIT=0`，不写入 |
| `cmd notification allow_listener --user 0 <COMPONENT>` | `Invalid listener - must be a ComponentName`，`EXIT=255`（Android 9 不认这个写法） |
| `settings put secure enabled_notification_listeners <COMPONENT>` | **能写入并读回**，但 `NotificationManagerService` 从不加载它，`dumpsys` 里始终 0 绑定 |
| `appops set app.xinghe.tv NOTIFICATION_LISTENER allow` | `Unknown operation string`（Android 9 没有这个 op） |
| `appops set app.xinghe.tv BIND_NOTIFICATION_LISTENER_SERVICE allow` | `Unknown operation string` |
| `appops set app.xinghe.tv ACCESS_NOTIFICATIONS allow` | `EXIT=0`，但对绑定无帮助 |

**电视上没有手动开启的入口**：`android.settings.NOTIFICATION_LISTENER_SETTINGS` 解析不到任何 Activity，用户没法在设置里自己打开。

对照 [xiaomi-mitv-mffu0.md](xiaomi-mitv-mffu0.md)（Android 14）「约 0.5 秒通知监听就被绑定」，**Android 9 上这条路径整个是失效的**。`install.sh` 里这行是 `|| true`，所以静默失败，安装时看不出来。

**根因（2026-10-21 查清）**：这台 `ro.config.low_ram=true`。Android 9 的 `NotificationManagerService.canUseManagedServices()` 在低内存设备上对所有非白名单包返回 false（白名单是 framework 资源 `config_allowedManagedServicesOnLowRamDevices`），`allow_listener`、`allow_dnd` 和读 `notification_policy.xml` 时都会过滤掉，所以通知监听、通知助手、勿扰条件提供者（ConditionProvider）三条路全部不通。`settings put secure enabled_notification_listeners` 也没用：Android 8.1 起已批准列表存在 `/data/system/notification_policy.xml`，这个设置只在迁移时读。`ro.*` 属性改不了，这条路在这台上没有办法。

## 待机强停后的复活：同步适配器（1.21）

改用 Android 的同步框架拉起 Aerial（`guard/RevivalSync.kt`）：

- Aerial 自己建一个账号（类型 `app.xinghe.tv`，设置 → 账号里能看到「Aerial 桌面守护」），在 `settings` 这个 authority 上注册同步适配器，`supportsUploading=true`。
- `SettingsProvider` 每次写设置都 `notifyChange(uri, null, syncToNetwork=true)`，`ContentService` 因此对 `settings` 安排一次本地同步，延迟 `sync.local_sync_delay`（默认 30 秒）。
- 强停本身就会触发它：`AccessibilityManagerService.onHandleForceStop` 把 Aerial 从 `enabled_accessibility_services` 里删掉，这就是一次设置写入。小米唤醒时把 `adb_enabled` 改成 0 也是。
- 同步任务的 JobScheduler 任务属于系统 uid，`cancelJobsForPackageAndUid` 只取消 Aerial 自己 uid 的任务，所以强停不会把它取消；`SyncManager` 绑定适配器服务时，系统会为已强停的包启动进程。
- 适配器只在守护没在运行、且守护确实被删掉需要写回时才算「复活」，否则什么都不做（每次改设置都会跑一次）。熄屏时的复活不计入 LeTV 的「频繁被杀」判断（`GuardSetup.noteRevival`）。
- 需要联网（同步任务要求有网络）和系统的自动同步开着（默认开）。

验证情况：

| 环境 | 结果 |
| --- | --- |
| Android 9 模拟器（`system-images;android-28;default;arm64-v8a`，`setprop ro.config.low_ram true` 后重启框架，`meminfo` 显示 `low-ram`） | `allow_listener` 静默失败，复现了这台的现象。原生桌面在前台时 `am force-stop`：进程一直不在，49 秒后 `Start proc … for service app.xinghe.tv/.guard.RevivalSyncService`，守护写回并接管桌面 |
| 同上，熄屏 → 强停 → 待机 60 秒 → 唤醒，连做 3 轮 | 每轮强停后约 30 秒复活，唤醒后 2 秒前台是 Aerial |
| 同上，只待机 15 秒，做 2 轮 | 唤醒后 22–25 秒才回到 Aerial（等同步的 30 秒延迟） |
| 小米 MFTR0（Android 14，固件同样有 `MitvActivityManagerService`），临时 `disallow_listener` 后 `am force-stop` | 46 秒后 `Start proc … for service RevivalSyncService`，**小米固件放行了同步框架拉起已强停的应用**。测完已 `allow_listener` 恢复 |
| 小米 MFTR0，通知监听开着时强停 | 照旧 0.3 秒由通知监听复活，同步适配器之后看到守护在运行，不重复动作 |

**这台 MFTP0 本身还没有用 1.21 实测**，需要用户在电视上验证真实遥控器待机。

## 两个缺陷叠加才会丢桌面

单独任何一个都不至于完全没救：

| 缺陷 | 后果 | 兜底 |
| --- | --- | --- |
| 升级后 APK 路径失效 → 守护绑不上 | HOME 退回 PatchWall | 重启 App 即可恢复 |
| 通知监听绑不上 | 被杀后无法立刻复活 | 1.21 起：同步适配器约 30–60 秒后复活 |

所以 1.20 及以前的症状是「装完正常、升级或待机后桌面消失、且再也回不来」。**`KEEP_ADB` 同样会丢**——它依赖 Aerial 的进程存活。1.21 起同步适配器复活后会先把 ADB 打开。

2026-10-21 一位用户在 1.18 上用实体遥控器测到了待机后的结果：唤醒后停在 PatchWall，ADB 被关（`Connection refused`），手动开 ADB 后 `stopped=true`、`enabled_accessibility_services` 里没有 Aerial、守护没在运行。这说明**这台的遥控器待机确实会强停 Aerial**（下文「待机与唤醒」那次没进入待机的测试不算数）。

## HOME 与守护

- 和 MFTR0 一样 HOME 被锁给 `com.mitv.tvhome`，靠桌面守护（无障碍）接管。绑定正常时日志是 `Service[label=Aerial 桌面守护, capabilities=8, eventTypes=TYPE_WINDOW_STATE_CHANGED]`。
- `enabled_accessibility_services` 用追加写入，原有的 `com.xiaomi.mitv.karaoke.service/...PuremicKeyService` 和 `com.xiaomi.voicecontrol/...VoiceAccessibilityService` 都要保留（这台有两个 voicecontrol 条目，写入时不要去重）。
- 用 `adb shell input keyevent KEYCODE_HOME` 注入的 HOME 不经过无障碍，**不能用来判断守护是否生效**，必须用实体遥控器。

## 开机自启

- `BootReceiver` + `RECEIVE_BOOT_COMPLETED: granted=true` 都在，**冷启动自启正常**。
- 2026-10-21 实测 `adb reboot`：约 33 秒后 `sys.boot_completed=1`，此时前台已经是 `app.xinghe.tv/.MainActivity`，没有落到 PatchWall。
- 开机后 `settings get global adb_enabled` 仍是 `1`（装了 `KEEP_ADB=1`）。
- 未停用任何系统应用。

## 待机与唤醒（未测准）

**没有测成。** 2026-10-21 试了一次遥控器电源键待机再唤醒，但日志显示 `mWakefulness` 前后都是 `Awake`，logcat 时间戳连续无缺口（网络 ADB 没断），说明**电视并没有真正进入待机**。所以这台待机唤醒后会怎样，目前未知。

要测的话建议用 adb 触发，比遥控器可靠：

```bash
adb shell input keyevent KEYCODE_POWER   # 待机
sleep 60
adb shell input keyevent KEYCODE_POWER   # 唤醒
```

## 出厂就停用的两个包（不是安装造成的）

这台上 `pm list packages -d` 有两个包，**先于 Aerial 安装就已停用**，和 `install.sh` 无关（脚本全文没有 `pm disable` / `uninstall`，两个包名在 `install.sh` 和 `restore.sh` 里都搜不到）：

| 包 | 状态 | 依据 |
| --- | --- | --- |
| `com.mitv.care` | `enabled=2`（用户停用） | `firstInstallTime=2022-06-26`，且 `pkgFlags` **无 `SYSTEM`**，codePath 在 `/data/app/`，是个普通应用 |
| `com.xiaomi.mitv.remotecontroller.service` | `enabled=2` | `pkgFlags=[SYSTEM ... UPDATED_SYSTEM_APP]`，`lastUpdateTime=2023-01-27` |

`restore.sh` 不要去动它们——还原时保持原样即可。

## 屏保

- `screensaver_components` 写成 `app.xinghe.tv/app.xinghe.tv.dream.AerialDream` 有效，重启后仍然保持。
- 小米不用 Android 标准 Dream（同 MFTR0），注意不要依赖 `screensaver_enabled`。

## 其他

- ADB 调试打开路径：设置 → 关于 → **产品型号** 连续按确认键进入开发者模式，然后 设置 → **账号与安全** → **ADB 调试**。
- 网络 ADB 在待机时会断（`Connection refused`），唤醒后能重连。`ping` 一直是通的，所以 `ping` 通但 5555 拒绝 = ADB 被关，不是电视掉线。
- 唤醒后 `global adb_enabled` 会被系统改成 0，`KEEP_ADB=1` 由守护服务写回 1（同 MFTR0）。

## 安装记录

```bash
adb connect <电视IP>:5555
curl -fsSL https://aerial.qust.me/install.sh | KEEP_ADB=1 TV=<电视IP> bash
```

输出：

```text
Downloading aerial-standard.apk from the latest release
Installing /var/folders/.../aerial-standard.apk
Aerial's screensaver is now the TV's screensaver.
Aerial will switch ADB debugging back on whenever the TV turns it off.
The firmware still sends Home to com.mitv.tvhome/.MainActivityUserMode; turning on Aerial Home Guard.
Home Guard is running. Press Home on the remote.
```

装的是 1.14（`versionCode=15`）。安装输出**没有提到通知监听失败**，这一项是后来排查才发现的。后续覆盖升级到 1.16 时触发了上面的 APK 路径问题。
