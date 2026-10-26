# 第三方组件声明

本仓库自身的代码以 MIT 发布（见 LICENSE）。Release 里每个 APK 都内嵌了第三方桌面，
它们的版权属于各自作者，本仓库只做打包与安装动作，不修改其内部内容（内嵌的 APK 与上游发布
的字节完全一致，sha256 可比对）。

| 组件 | 版本 | 上游 | 许可 | 说明 |
|---|---|---|---|---|
| ffalcontrol | 0.1.4 | https://github.com/xnklerEd/ffalcontrol | MIT | 主体代码来源。本仓库在其基础上增加了内置桌面、HOME 角色申请、界面与构建脚本 |
| Aerial | 1.56 (`aerial-standard`) | https://github.com/pengchujin/aerial-tv | MIT | 作为可选内置桌面；其配色与排版（Palette / Type）同时用于本仓库 UI |
| CleanYourTV | 3.0.0 | https://github.com/mmaotv/AerialToolbox | 见该仓库 | 作为可选内置桌面 |
| Emotn UI | 1.1.0.2 | Emotn | 闭源免费软件，版权归 Emotn | 作为可选内置桌面，仅原样打包分发。若版权方有异议，删除对应 Release 资产即可 |

各内嵌桌面的 sha256（与 `vendor/` 中同名文件一致）：

```
70c888de8dde4be08b22182e152e10611a143c52b72724f972e47e636bd1c7fc  CleanYourTV-3.0.0.apk
df1fee83c328de292ace6ae61db899f73b0302bcb07b88934a88672c1e448b64  aerial-standard.apk
701ade3cdfd13a4efd68f17f27b31173df6c53bdc1a7fa0301294cf6dd2732ff  EmotnUI-1.1.0.2.apk
```

如需完全不含第三方的构建，直接 `TOKEN=random ./build.sh` 时替换 `vendor/` 中的 APK 为你自己的即可；
源码本身不包含任何第三方桌面。
