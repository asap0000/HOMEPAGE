`ANDROID_SERIAL=<仮想の端末> ./gradlew :buscourse:connectedKensaAndroidTest` で実行します。
入るのは `com.istech.buscourse.kensa` と試験用の APK だけです（本番・debug・ナビ専科には触れません）。
結果は `build/outputs/androidTest-results/connected/` に出ます。
