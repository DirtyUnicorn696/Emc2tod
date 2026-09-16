# Компилятор кода — Android-приложение

WebView-обёртка вокруг генератора Perchance «Компилятор кода»:
<https://perchance.org/compapk>

## Сборка APK

Сборку выполняет GitHub Actions — установка Android SDK не нужна:

1. откройте вкладку **Actions**;
2. дождитесь зелёной галочки у задачи «Build APK»;
3. в разделе **Artifacts** скачайте `codecompiler-release-apk` (или `codecompiler-debug-apk`);
4. распакуйте архив и установите `.apk` на телефон.

Сборка запускается автоматически после каждого изменения файлов
(и вручную — кнопкой «Run workflow»).

APK подписан debug-ключом: для личной установки и передачи друзьям этого
достаточно, для Google Play нужен собственный release-ключ.

## Структура

| Путь | Что это |
| --- | --- |
| `app/src/main/java/dev/codecompiler/android/MainActivity.kt` | WebView, экран ошибки, мост сохранения файлов |
| `app/src/main/AndroidManifest.xml` | разрешение INTERNET, одна activity |
| `app/src/main/res/**` | название, цвета, тема, иконка `</>` |
| `.github/workflows/build-apk.yml` | облачная сборка |

Адрес генератора задаётся в `MainActivity.kt`:

```kotlin
private const val START_URL = "https://perchance.org/compapk"
```
