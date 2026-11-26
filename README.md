# 手机副屏 PhoneLink

把一台旧手机装在电动车车把上，当作主手机的“播放副屏”：显示正在播放的歌曲或有声书（封面、标题、进度），可以切歌、暂停、快进快退、切换来源 App。主手机放在口袋里照常使用。

- 两台手机通过**蓝牙直连**，不需要流量或热点
- 主手机通过系统“通知使用权”读取播放器的媒体会话，不需要 root，支持网易云、QQ 音乐、酷狗、喜马拉雅、番茄畅听等
- 副屏建议 root：开机自启、静默更新

## 安装

到 [Releases](https://github.com/chen-pi-2007/phonelink/releases) 下载 APK，两台手机装同一个包，首次打开时选择“主手机”或“副屏”。之后 App 会自动检查更新：
- 主手机：提示有新版，点一下安装
- 副屏：从主手机蓝牙接收新版，或自己联网下载，root 静默安装

## 开发

- JDK 17，Android SDK 34
- 签名证书不在本仓库，构建时从 `D:\tool\keys\phonelink` 读取（环境变量 `PHONELINK_KEYS_DIR` 可改）
- 发版：改 `app/build.gradle` 的 `versionCode` / `versionName`，然后 `python tools/release.py "更新说明"`
