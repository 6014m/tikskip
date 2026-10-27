# TikSkip

Auto-swipes past ads, lives, slideshows and still images on TikTok. Android accessibility service — reads the screen labels, swipes up when it sees one you've toggled off.

## Install
Releases → `latest` → `TikSkip.apk`. Open the app → ENABLE → flip TikSkip on in Accessibility settings. Android will warn you; that's expected for anything that reads the screen.

## If it stops working
TikTok renamed a label. The detection words live at the top of `SkipService.kt`.
