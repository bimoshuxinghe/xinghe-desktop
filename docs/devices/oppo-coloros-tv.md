# OPPO K9 65 寸（ColorOS TV 2.4，`OPPOCNM652`）

一台 65 寸 OPPO K9：系统是 ColorOS TV 2.4（基于 Android 9），2 GB 内存，只有 32 位应用能装
（`PPR2.180905.006.A1`，固件 2023-01-12）。下面写的都是这台机器上实际试出来的结果，
不是在别的 OPPO 机型上推断的。

## 先打开 ADB

1. 设置 → 系统 → 关于本机 → **设备型号**，然后在遥控器上按 上、上、右、右、确定。
   页面会提示「还需执行 N 步」，按对了开发者选项才会出现。
2. 设置 → 系统 → **开发者选项**，打开「USB 调试」。
3. 电脑上执行 `adb connect <电视IP>:5555`，就连上了。

- 不用配对码，也没有「无线调试」这一项（Android 9 没有）。
- 开一次就行，重启后仍然是开着的。
- **电视待机（熄屏）时会断网，ADB 会跟着断**，唤醒后重新 connect 一次即可。
  如果重启后 `adb connect` 说 `No route to host`，但电视网络明明通，先 `adb kill-server` 再连。

## 为什么不能直接装

OPPO 只允许三个固定包名的应用当桌面。其它应用只要声明「我是桌面」，安装就会被拒绝：

```
INSTALL_FAILED_DUPLICATE_PERMISSION: App installation is banned, because of launcher categories
```

这句报错容易误导人：它既不是权限冲突，也不是签名问题。能用的包名只有
`com.heytap.tv.launcher`、`com.oneplus.tv.launcher2`、`com.oppo.tv.launcher2`。

这台机器是把 Aerial 的 `standard` 版改成 `com.oneplus.tv.launcher2` 之后装的，
之后授权、通知监听、无障碍、悬浮窗都正常。`guard` 版本来就不声明桌面，
用原来的包名可以直接装（实测），只是接管桌面的效果没在这台机器上验证。

### 改包名的话，有两处内部名字要一起改

不改也能装上，但用起来会出问题：

1. **装完一打开就闪退**，报错是
   `Permission com.oneplus.tv.launcher2.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION is required…`。
   原因是应用内部会用一个「包名 + 固定后缀」的权限名注册广播，这个权限名是打包时按
   `applicationId` 写死的，改了包名就对不上。manifest 里的声明要一起改成新包名。
2. **官方版、`guard` 版会装不进来**，报错
   `INSTALL_FAILED_CONFLICTING_PROVIDER … app.xinghe.tv.androidx-startup is already used by …`。
   这个内部组件名同样写死了原包名，改了包名后它和官方包撞车，也要改成新包名。

## 还差一步：告诉开机流程「这是要启动的桌面」

OPPO 开机时只认带 `com.heytap.tv.launcher.category.MODERN_HOME` 这个标记的桌面。
没有这个标记的第三方桌面，系统会一直等不到「该起来的桌面」，
电视就卡在品牌开机动画里，不会自己进桌面。

给 HOME 的启动声明加上这个标记即可：加上之后，开机 77 秒内就自动进了 Aerial。

## 实测结果

| 项目 | 结果 |
|---|---|
| 安装 | 成功，改包名后用同样方式覆盖安装，壁纸、照片、图标排序都保留 |
| 授权 | `WRITE_SECURE_SETTINGS`、通知监听、无障碍、悬浮窗都正常授予 |
| 开机 | 自动进 Aerial，不再卡开机动画 |
| 待机唤醒 | 回到 Aerial。熄屏几分钟后的长待机没测（待机断网，ADB 跟不上） |
| 按 HOME | 直接回 Aerial；原桌面已禁用，不会先闪一下原桌面 |
| 被强制停止后 | 约 0.5 秒被通知监听拉回来，8 秒回到前台，15 秒左右守护重新连上 |
| 航拍屏保 | 正常，Apple 的影片走的是国内 CDN，换片不黑屏 |
| 天气 | 正常显示 |
| 手机配置页 | 正常打开（二维码扫码即可） |
| 内存占用 | 前台约 66 MB |

## 已知问题

- **更新一版之后，桌面守护偶尔不会自己回来。** 表现是按 HOME 或开机时不再自动拉起 Aerial，
  但手动打开一次就恢复。最简单的修法是重启电视；不想重启的话，
  执行 `adb shell am force-stop com.oneplus.tv.launcher2`，
  通知监听会把 Aerial 重新拉起来，守护也会跟着重新连上。
- 装第二个「桌面」应用会清掉默认桌面的选择，装完之后要把默认桌面重新指回 Aerial：
  `adb shell cmd package set-home-activity com.oneplus.tv.launcher2/app.xinghe.tv.MainActivity`。
  这台机器的设置里也有「设置主屏幕」一项，可以当开关用。
