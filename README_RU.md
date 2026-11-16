# DigiDoc Converter — PDF → TXT + OCR

## Что умеет

- Извлекает текст из PDF с текстовым слоем (быстро)
- Если текстового слоя мало или нет — **автоматически запускает OCR**
- Поддерживает **русский + английский**
- Есть кнопка «Принудительно OCR» для сканов
- Работает полностью офлайн (после добавления языковых моделей)

## Что пока нет

- DJVU
- Пакетная конвертация многих файлов сразу
- Распознавание рукописного текста

## Важно: языковые модели для OCR

Без файлов `eng.traineddata` и `rus.traineddata` OCR работать не будет.

### Как добавить модели

1. Скачай файлы:
   - https://github.com/tesseract-ocr/tessdata/raw/main/eng.traineddata
   - https://github.com/tesseract-ocr/tessdata/raw/main/rus.traineddata

2. Положи их в проект по пути:
   ```
   DigiDocConverter/app/src/main/assets/tessdata/
   ```

   Структура должна быть такой:
   ```
   app/src/main/assets/tessdata/eng.traineddata
   app/src/main/assets/tessdata/rus.traineddata
   ```

3. Пересобери APK.

Размер моделей: примерно 20–25 МБ. APK станет тяжелее.

## Как собрать APK через GitHub

1. Создай новый пустой репозиторий на GitHub.
2. Загрузи **всю папку DigiDocConverter** (включая `.github` и `assets`).
3. Перейди во вкладку **Actions**.
4. Дождись зелёной галочки у workflow **Build Android APK**.
5. Скачай артефакт `DigiDocConverter-debug-apk` → внутри `app-debug.apk`.

## Технические сведения

- applicationId: `ru.digidoc.converter`
- minSdk: 23 (Android 6.0)
- targetSdk: 35
- OCR: tess-two (Tesseract) + PDFBox для рендера страниц
- Языки OCR: rus+eng

## Примечание

Это debug-сборка. Для личного использования подходит.
