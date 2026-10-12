# Build and download the ThorVault APK without building on your Mac

This repository includes a GitHub Actions workflow that builds the local .NET WebAssembly engine, bundles it into the Android app, builds the APK, and uploads the APK as a downloadable workflow artifact. The app version remains **0.12.23-alpha**.

## Steps

1. Upload/push this project folder to a GitHub repository. Keep the `.github/workflows/build-android-apk.yml` file in the repository.
2. Open the repository's **Actions** tab. If GitHub asks you to enable Actions, enable them.
3. Open **Build installable ThorVault APK** and choose **Run workflow**. A normal push also triggers the workflow.
4. Wait for the run to finish with a green check mark, open that run, and download the artifact named **ThorVault-v0.12.23-alpha-APK**.
5. Extract the downloaded artifact ZIP. It contains `app-debug.apk`. Transfer that APK to the AYN Thor and install it. If Android blocks the installation, allow the file manager or browser you used to install unknown apps.

The generated debug APK is intended for sideloading/testing. The workflow must finish successfully before an APK artifact will be available. A failed run's log will show which build step needs attention.

The first build needs an internet connection on GitHub's build runner to download the .NET/Android dependencies. Once the APK is installed, save parsing and the currently implemented save-transfer engine are bundled locally; Vault sync and server backups still need the server.
