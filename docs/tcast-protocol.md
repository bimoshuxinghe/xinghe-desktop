# T-Cast 6553 补充指令与加密模式

> [devices/tcl-mt5879-cn.md](devices/tcl-mt5879-cn.md) 记录了安装用的 `159 >> 223>>INSTALL >> 223>>PROGRESS` 这一条链路；本文补的是**加密协商**与**另外几条可用指令**。来源：#53（作者 @sherlock2221b）。

## 1. 两种握手：明文 vs 加密

现有 `install.sh` 用的握手是 `159>>1>>0`，电视回 `253>>1>>0`，全程明文——这条没问题。

但如果按另一种格式握手（名字 + 客户端 ID）：

```
159>><clientName>>1>><clientId>>1
```

电视回包里会多出一个字段：**第 6 个字段是 `algorithm`**。当它 `= 1` 时，**之后的所有消息（收与发）都是 AES 加密的**：

| 参数 | 值 |
|---|---|
| 算法 | AES-128-CBC，PKCS7 填充 |
| key | `tnscreentnscreen`（16 字节 ASCII） |
| IV | 字节 `1234567890abcdef1234567890abcdef`（**不是**这串 ASCII 文本本身，而是其十六进制解码） |

- 发指令时**用明文也能被电视接受**（本机实测 `223>>INSTALL` 明文发送照样成功），但此时**回包是密文，需用上面的 key/IV 解密**才能看懂。
- 心跳 `150>>`，电视约 **20 秒**无流量会断链。
- 解密时不要重复调 `finalize()`，否则会抛异常被吞掉、误判成「密钥不对」。

## 2. 补充指令表

| 指令 | 作用 | 备注 |
|---|---|---|
| **`263>><1 activity / 2 broadcast>><intentJSON>`** | 🔥 发任意 Intent，等于远程 `am start` | 可指定 `component{pkg,cls}` + `extraList` + `flagList` |
| `225>>` | 电视截图，回包带图片 URL | 视频走独立硬件图层，**截出来是黑的**，字幕和时钟可见 |
| `149>><keyCode>` | 模拟遥控按键 | up=11 down=12 left=13 right=14 ok=15 back=16 menu=18 home=19 power=20 vol_up=21 mute=23 source=29 enter=68；数字 1-9 = 1-9、0 = 10 |
| `223>>GET` | 应用列表 | 回包按 `>` 切分，每条 `包名::显示名` |
| `223>>OPEN>><包名>` | 启动应用 | **只接受包名**，不接受 component |
| `151>>x>>y` | 鼠标移动 | |
| `155>><text>` | IME 文本注入 | |
| `280>>2 / 280>>3` | 亮度 | |
| `242>>` | 系统音量 | |
| `160>><imei>><name>` | 连接提示 | |

### `263` 的 Intent JSON 字段

```json
{
  "action": "android.intent.action.MAIN",
  "category": null,
  "component": { "pkg": "app.xinghe.tv", "cls": "app.xinghe.tv.MainActivity" },
  "extraList": [ { "type": 0, "key": "k", "value": "v" } ],
  "flagList": [268435456],
  "launchType": 1
}
```

- `extraList` 的 `type`：`0`=String、`1`=int、`2`=boolean
- `flagList` 常用 `268435456`（`FLAG_ACTIVITY_NEW_TASK`）
- `launchType`：`0`=service/window、`1`=activity

⚠ 本机实测：**`component` 置 `null` 走 action-only 时，电视似乎不派发**（`android.settings.SETTINGS`、
`android.settings.ACCESSIBILITY_SETTINGS` 均无反应）。要拉起具体页面**必须指定 component**，
而这需要先知道固件里真实的包名/类名（可用 `223>>GET` 拿）。

## 3. 三个必踩的坑

1. 🔴 **每次新建 TCP 连接，TCL 的侧边设置面板会自动关闭**（相当于「手机已连接」提示抢走焦点）。
   ⇒ 「打开设置 → 按键导航 → 截图确认」必须**在同一个连接里一口气跑完**，否则按键会全部落到桌面。
2. 🔴 **下载地址不接受局域网 `http://<IP>:<非标端口>/...`**。MT5879 文档的做法是 `adb reverse` 后访问
   `http://127.0.0.1:8765/Aerial.apk`——**本机没有 ADB 就做不了 reverse**，实测电视根本没发起请求
   （本机 macOS 防火墙为 disabled，已排除拦截）。⇒ 无 ADB 场景必须给**公网可访问 URL**。
3. 多屏互动的安装提示框**默认焦点在「拒绝」上**，要先按「右」再按 OK（与现有文档一致）。

## 4. 参考实现

- 逆向来源：**`DusKing1/ha-tcl-tv`**（Home Assistant 集成，逆向了 TCL+/T-Cast「tnscreen」SDK），
  仓库内有 **588 行的 `docs/protocol.md`**，是目前能找到的最完整公开资料。
- 本机可用的客户端已归档：`tcast-ctl.py`（指令收发）、`tv.py`（含正确解密 + `225` 截图下载）。
