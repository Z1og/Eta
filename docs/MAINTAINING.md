# Eta Fork 自维护说明

本仓库是 [Mangi-11/Eta](https://github.com/Mangi-11/Eta) 的 fork（`zelongpan/Eta`），用于个人后续维护。完整的使用、安装与功能说明见上游 `README.md`。

## 许可证

上游使用 **PolyForm Noncommercial License 1.0.0**（见根目录 `LICENSE`），本 fork 同样适用：

- ✅ 允许：个人学习、研究、修改、非商业用途
- ❌ 禁止：销售本项目、源码、APK 或修改版本；基于本项目提供付费分发、收费代装等商业服务
- 🔑 如需商业授权，必须联系上游作者 Mangi-11

## 构建

GitHub Actions（`.github/workflows/android-release.yml`，workflow 名 `Eta Build`）在以下时机自动构建 **Debug 与 Release APK**：

- push 到 `main` 分支
- 手动触发（Actions 页面 → Eta Build → Run workflow）

构建产物在每次 run 的 **Artifacts** 中（`app-debug.apk` / `app-release.apk`，保留 14 天）。

### Release 签名

Release APK 使用本 fork 自有的签名 keystore，通过以下 Secrets 注入：

| Secret | 内容 |
|---|---|
| `ETA_RELEASE_KEYSTORE_BASE64` | keystore 文件 base64 |
| `ETA_RELEASE_STORE_PASSWORD` | store 密码 |
| `ETA_RELEASE_KEY_ALIAS` | key 别名 |
| `ETA_RELEASE_KEY_PASSWORD` | key 密码 |

⚠️ **keystore 与密码务必妥善备份**（本地副本位于工作区外的 `eta-keys/` 目录）。一旦丢失，后续构建的签名将不一致，已安装的 App 无法覆盖升级，只能卸载重装。

## 同步上游更新

```bash
git fetch upstream
git merge upstream/main
git push origin main   # push 后 Actions 会自动重新构建
```

注意：本 fork 对 `app/build.gradle.kts` 或 workflow 的任何改动都可能与上游冲突，merge 时需手动解决。若无需本地定制，建议直接使用上游文件。

## 版本与标签

`git tag` 包含上游发布的所有版本标签（v1.0.0 ~ v2.5.1）。`app/build.gradle.kts` 中的 `versionCode` / `versionName` 与上游保持一致。
- 最近一次触发验证时间：17:00:55Z
