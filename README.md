# Oplus Assistant Hook (Fork)

本仓库是 [Andrea-lyz/Oplus-Assistant-Hook](https://github.com/Andrea-lyz/Oplus-Assistant-Hook) 的扩展 fork。

在原模块替换电源键长按为 Gemini / 一圈即搜的基础上，增加了 **自定义助理** 选项——可从已注册 VoiceInteractionService 的应用列表中选取（如 Operit），或手动输入包名，让长按电源键打开任意小助手应用。

## 与原版的差异

- 新增第四种电源键模式：**自定义助理**
- 应用选择器列出所有注册了 `VoiceInteractionService` 的应用
- 支持手动输入包名（兜底）
- 暗色模式自动跟随系统
- 其余功能与原版一致

## 安装

1. 设备已 Root，安装 LSPosed
2. 安装模块 APK
3. LSPosed 中启用模块，作用域勾选 `system`、`com.android.systemui`
4. 重启系统
5. 打开模块应用 → 电源键长按 → 选择 **自定义助理** → 选中目标应用

## 构建

```bash
./gradlew assembleDebug
```

## 许可证

[Apache License 2.0](LICENSE)

原仓库：https://github.com/Andrea-lyz/Oplus-Assistant-Hook